package net.vonforst.evmap.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.google.android.libraries.maps.GoogleMap
import com.google.android.libraries.maps.model.LatLngBounds
import com.google.android.libraries.places.api.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.vonforst.evmap.api.availability.ChargeLocationStatus
import net.vonforst.evmap.api.availability.getAvailability
import net.vonforst.evmap.api.goingelectric.*
import net.vonforst.evmap.storage.*
import net.vonforst.evmap.ui.cluster
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException

data class MapPosition(val bounds: LatLngBounds, val zoom: Float)

internal fun getClusterDistance(zoom: Float): Int? {
    return when (zoom) {
        in 0.0..7.0 -> 100
        in 7.0..11.5 -> 75
        in 11.5..12.5 -> 60
        in 12.5..13.0 -> 45
        else -> null
    }
}

class MapViewModel(application: Application, geApiKey: String) : AndroidViewModel(application) {
    private var api = GoingElectricApi.create(geApiKey, context = application)
    private var db = AppDatabase.getInstance(application)
    private var prefs = PreferenceDataSource(application)
    private var chargepointLoader: Job? = null

    val bottomSheetState: MutableLiveData<Int> by lazy {
        MutableLiveData<Int>()
    }

    val mapPosition: MutableLiveData<MapPosition> by lazy {
        MutableLiveData<MapPosition>()
    }
    private val filterValues: LiveData<List<FilterValue>> by lazy {
        db.filterValueDao().getFilterValues()
    }
    private val plugs: LiveData<List<Plug>> by lazy {
        PlugRepository(api, viewModelScope, db.plugDao(), prefs).getPlugs()
    }
    private val networks: LiveData<List<Network>> by lazy {
        NetworkRepository(api, viewModelScope, db.networkDao(), prefs).getNetworks()
    }
    private val chargeCards: LiveData<List<ChargeCard>> by lazy {
        ChargeCardRepository(api, viewModelScope, db.chargeCardDao(), prefs).getChargeCards()
    }
    private val filters = getFilters(application, plugs, networks, chargeCards)

    private val filtersWithValue: LiveData<List<FilterWithValue<out FilterValue>>> by lazy {
        filtersWithValue(filters, filterValues, filtersActive)
    }

    val filtersCount: LiveData<Int> by lazy {
        MediatorLiveData<Int>().apply {
            value = 0
            addSource(filtersWithValue) { filtersWithValue ->
                value = filtersWithValue.count {
                    it.filter.defaultValue() != it.value
                }
            }
        }
    }
    val chargepoints: MediatorLiveData<Resource<List<ChargepointListItem>>> by lazy {
        MediatorLiveData<Resource<List<ChargepointListItem>>>()
            .apply {
                value = Resource.loading(emptyList())
                listOf(mapPosition, filtersWithValue).forEach {
                    addSource(it) {
                        reloadChargepoints()
                    }
                }
            }
    }

    val chargerSparse: MutableLiveData<ChargeLocation> by lazy {
        MutableLiveData<ChargeLocation>()
    }
    val chargerDetails: MediatorLiveData<Resource<ChargeLocation>> by lazy {
        MediatorLiveData<Resource<ChargeLocation>>().apply {
            addSource(chargerSparse) { charger ->
                if (charger != null) {
                    loadChargerDetails(charger)
                } else {
                    value = null
                }
            }
        }
    }
    val charger: MediatorLiveData<Resource<ChargeLocation>> by lazy {
        MediatorLiveData<Resource<ChargeLocation>>().apply {
            addSource(chargerDetails) {
                value = when (it?.status) {
                    null -> null
                    Status.SUCCESS -> Resource.success(it.data)
                    Status.LOADING -> Resource.loading(chargerSparse.value)
                    Status.ERROR -> Resource.error(it.message, chargerSparse.value)
                }
            }
        }
    }
    val availability: MediatorLiveData<Resource<ChargeLocationStatus>> by lazy {
        MediatorLiveData<Resource<ChargeLocationStatus>>().apply {
            addSource(chargerSparse) { charger ->
                if (charger != null) {
                    viewModelScope.launch {
                        loadAvailability(charger)
                    }
                } else {
                    value = null
                }
            }
        }
    }
    val myLocationEnabled: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>()
    }
    val layersMenuOpen: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>().apply {
            value = false
        }
    }

    val favorites: LiveData<List<ChargeLocation>> by lazy {
        db.chargeLocationsDao().getAllChargeLocations()
    }

    val searchResult: MutableLiveData<Place> by lazy {
        MutableLiveData<Place>()
    }

    val mapType: MutableLiveData<Int> by lazy {
        MutableLiveData<Int>().apply {
            value = GoogleMap.MAP_TYPE_NORMAL
        }
    }

    val mapTrafficEnabled: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>().apply {
            value = false
        }
    }

    val filtersActive: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>().apply {
            value = true
        }
    }

    fun setMapType(type: Int) {
        mapType.value = type
    }

    fun insertFavorite(charger: ChargeLocation) {
        viewModelScope.launch {
            db.chargeLocationsDao().insert(charger)
        }
    }

    fun deleteFavorite(charger: ChargeLocation) {
        viewModelScope.launch {
            db.chargeLocationsDao().delete(charger)
        }
    }

    fun reloadChargepoints() {
        val pos = mapPosition.value ?: return
        val filters = filtersWithValue.value ?: return
        loadChargepoints(pos, filters)
    }

    private fun loadChargepoints(
        mapPosition: MapPosition,
        filters: List<FilterWithValue<out FilterValue>>
    ) {
        chargepointLoader?.cancel()

        chargepoints.value = Resource.loading(chargepoints.value?.data)
        val bounds = mapPosition.bounds
        val zoom = mapPosition.zoom
        chargepointLoader = viewModelScope.launch {
            chargepoints.value = getChargepointsWithFilters(bounds, zoom, filters)
        }
    }

    private suspend fun getChargepointsWithFilters(
        bounds: LatLngBounds,
        zoom: Float,
        filters: List<FilterWithValue<out FilterValue>>
    ): Resource<List<ChargepointListItem>> {
        val freecharging = getBooleanValue(filters, "freecharging")
        val freeparking = getBooleanValue(filters, "freeparking")
        val open247 = getBooleanValue(filters, "open_247")
        val barrierfree = getBooleanValue(filters, "barrierfree")
        val excludeFaults = getBooleanValue(filters, "exclude_faults")
        val minPower = getSliderValue(filters, "min_power")
        val minConnectors = getSliderValue(filters, "min_connectors")

        val connectorsVal = getMultipleChoiceValue(filters, "connectors")
        if (connectorsVal.values.isEmpty() && !connectorsVal.all) {
            // no connectors chosen
            return Resource.success(emptyList())
        }
        val connectors = formatMultipleChoice(connectorsVal)

        val chargeCardsVal = getMultipleChoiceValue(filters, "chargecards")
        if (chargeCardsVal.values.isEmpty() && !chargeCardsVal.all) {
            // no chargeCards chosen
            return Resource.success(emptyList())
        }
        val chargeCards = formatMultipleChoice(chargeCardsVal)

        val networksVal = getMultipleChoiceValue(filters, "networks")
        if (networksVal.values.isEmpty() && !networksVal.all) {
            // no networks chosen
            return Resource.success(emptyList())
        }
        val networks = formatMultipleChoice(networksVal)

        // do not use clustering if filters need to be applied locally.
        val useClustering = zoom < 13
        val geClusteringAvailable = minConnectors <= 1
        val useGeClustering = useClustering && geClusteringAvailable
        val clusterDistance = if (useClustering) getClusterDistance(zoom) else null

        var startkey: Int? = null
        val data = mutableListOf<ChargepointListItem>()
        do {
            // load all pages of the response
            try {
                val response = api.getChargepoints(
                    bounds.southwest.latitude,
                    bounds.southwest.longitude,
                    bounds.northeast.latitude,
                    bounds.northeast.longitude,
                    clustering = useGeClustering,
                    zoom = zoom,
                    clusterDistance = clusterDistance,
                    freecharging = freecharging,
                    minPower = minPower,
                    freeparking = freeparking,
                    open247 = open247,
                    barrierfree = barrierfree,
                    excludeFaults = excludeFaults,
                    plugs = connectors,
                    chargecards = chargeCards,
                    networks = networks,
                    startkey = startkey
                )
                if (!response.isSuccessful || response.body()!!.status != "ok") {
                    return Resource.error(response.message(), chargepoints.value?.data)
                } else {
                    val body = response.body()!!
                    data.addAll(body.chargelocations)
                    startkey = body.startkey
                }
            } catch (e: IOException) {
                return Resource.error(e.message, chargepoints.value?.data)
            }
        } while (startkey != null && startkey < 10000)

        var result = data.filter { it ->
            // apply filters which GoingElectric does not support natively
            if (it is ChargeLocation) {
                it.chargepoints
                    .filter { it.power >= minPower }
                    .filter { if (!connectorsVal.all) it.type in connectorsVal.values else true }
                    .sumBy { it.count } >= minConnectors
            } else {
                true
            }
        }
        if (!geClusteringAvailable && useClustering) {
            // apply local clustering if server side clustering is not available
            Dispatchers.IO.run {
                result = cluster(result, zoom, clusterDistance!!)
            }
        }

        return Resource.success(result)
    }

    private fun formatMultipleChoice(connectorsVal: MultipleChoiceFilterValue) =
        if (connectorsVal.all) null else connectorsVal.values.joinToString(",")

    private fun getBooleanValue(
        filters: List<FilterWithValue<out FilterValue>>,
        key: String
    ) = (filters.find { it.value.key == key }!!.value as BooleanFilterValue).value

    private fun getSliderValue(
        filters: List<FilterWithValue<out FilterValue>>,
        key: String
    ) = (filters.find { it.value.key == key }!!.value as SliderFilterValue).value

    private fun getMultipleChoiceValue(
        filters: List<FilterWithValue<out FilterValue>>,
        key: String
    ) = filters.find { it.value.key == key }!!.value as MultipleChoiceFilterValue

    private suspend fun loadAvailability(charger: ChargeLocation) {
        availability.value = Resource.loading(null)
        availability.value = getAvailability(charger)
    }

    private fun loadChargerDetails(charger: ChargeLocation) {
        chargerDetails.value = Resource.loading(null)
        api.getChargepointDetail(charger.id).enqueue(object :
            Callback<ChargepointList> {
            override fun onFailure(call: Call<ChargepointList>, t: Throwable) {
                chargerDetails.value = Resource.error(t.message, null)
                t.printStackTrace()
            }

            override fun onResponse(
                call: Call<ChargepointList>,
                response: Response<ChargepointList>
            ) {
                if (!response.isSuccessful || response.body()!!.status != "ok") {
                    chargerDetails.value = Resource.error(response.message(), null)
                } else {
                    chargerDetails.value =
                        Resource.success(response.body()!!.chargelocations[0] as ChargeLocation)
                }
            }
        })
    }
}