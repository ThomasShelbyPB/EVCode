package net.vonforst.evmap.storage

import android.content.Context
import androidx.preference.PreferenceManager
import com.car2go.maps.AnyMap
import net.vonforst.evmap.R
import net.vonforst.evmap.viewmodel.FILTERS_CUSTOM
import net.vonforst.evmap.viewmodel.FILTERS_DISABLED
import java.time.Instant

class PreferenceDataSource(val context: Context) {
    val sp = PreferenceManager.getDefaultSharedPreferences(context)

    var navigateUseMaps: Boolean
        get() = sp.getBoolean("navigate_use_maps", true)
        set(value) {
            sp.edit().putBoolean("navigate_use_maps", value).apply()
        }

    var lastPlugUpdate: Instant
        get() = Instant.ofEpochMilli(sp.getLong("last_plug_update", 0L))
        set(value) {
            sp.edit().putLong("last_plug_update", value.toEpochMilli()).apply()
        }

    var lastNetworkUpdate: Instant
        get() = Instant.ofEpochMilli(sp.getLong("last_network_update", 0L))
        set(value) {
            sp.edit().putLong("last_network_update", value.toEpochMilli()).apply()
        }

    var lastChargeCardUpdate: Instant
        get() = Instant.ofEpochMilli(sp.getLong("last_chargecard_update", 0L))
        set(value) {
            sp.edit().putLong("last_chargecard_update", value.toEpochMilli()).apply()
        }

    /**
     * Stores the current filtering status, which is either the ID of a filter profile or
     * one of FILTERS_DISABLED, FILTERS_CUSTOM
     */
    var filterStatus: Long
        get() =
            sp.getLong(
                "filter_status",
                // migration from versions before filter profiles were implemented
                if (sp.getBoolean("filters_active", true))
                    FILTERS_CUSTOM else FILTERS_DISABLED
            )
        set(value) {
            sp.edit().putLong("filter_status", value).apply()
        }

    /**
     * Stores the last filter profile which was selected
     * (excluding FILTERS_DISABLED, but including FILTERS_CUSTOM)
     */
    var lastFilterProfile: Long
        get() = sp.getLong("last_filter_profile", FILTERS_CUSTOM)
        set(value) {
            sp.edit().putLong("last_filter_profile", value).apply()
        }


    val language: String
        get() = sp.getString("language", "default")!!

    val darkmode: String
        get() = sp.getString("darkmode", "default")!!

    val mapProvider: String
        get() = sp.getString(
            "map_provider",
            context.getString(R.string.pref_map_provider_default)
        )!!

    var mapType: AnyMap.Type
        get() = AnyMap.Type.valueOf(sp.getString("map_type", null) ?: AnyMap.Type.NORMAL.toString())
        set(type) {
            sp.edit().putString("map_type", type.toString()).apply()
        }

    var mapTrafficEnabled: Boolean
        get() = sp.getBoolean("map_traffic_enabled", false)
        set(value) {
            sp.edit().putBoolean("map_traffic_enabled", value).apply()
        }

    var welcomeDialogShown: Boolean
        get() = sp.getBoolean("welcome_dialog_shown", false)
        set(value) {
            sp.edit().putBoolean("welcome_dialog_shown", value).apply()
        }

    var update060AndroidAutoDialogShown: Boolean
        get() = sp.getBoolean("update_0.6.0_androidauto_dialog_shown", false)
        set(value) {
            sp.edit().putBoolean("update_0.6.0_androidauto_dialog_shown", value).apply()
        }

    var chargepriceMyVehicle: String?
        get() = sp.getString("chargeprice_my_vehicle", null)
        set(value) {
            sp.edit().putString("chargeprice_my_vehicle", value).apply()
        }

    var chargepriceMyVehicleDcChargeports: List<String>?
        get() = sp.getString("chargeprice_my_vehicle_dc_chargeports", null)?.split(",")
        set(value) {
            sp.edit().putString("chargeprice_my_vehicle_dc_chargeports", value?.joinToString(","))
                .apply()
        }

    var chargepriceMyTariffs: Set<String>?
        get() = sp.getStringSet("chargeprice_my_tariffs", null)
        set(value) {
            sp.edit().putStringSet("chargeprice_my_tariffs", value).apply()
        }

    var chargepriceMyTariffsAll: Boolean
        get() = sp.getBoolean("chargeprice_my_tariffs_all", true)
        set(value) {
            sp.edit().putBoolean("chargeprice_my_tariffs_all", value).apply()
        }

    var chargepriceNoBaseFee: Boolean
        get() = sp.getBoolean("chargeprice_no_base_fee", false)
        set(value) {
            sp.edit().putBoolean("chargeprice_no_base_fee", value).apply()
        }

    var chargepriceShowProviderCustomerTariffs: Boolean
        get() = sp.getBoolean("chargeprice_show_provider_customer_tariffs", false)
        set(value) {
            sp.edit().putBoolean("chargeprice_show_provider_customer_tariffs", value).apply()
        }

    var chargepriceCurrency: String
        get() = sp.getString("chargeprice_currency", null) ?: "EUR"
        set(value) {
            sp.edit().putString("chargeprice_currency", value).apply()
        }

    var chargepriceBatteryRange: List<Float>
        get() = listOf(
            sp.getFloat("chargeprice_battery_range_min", 20f),
            sp.getFloat("chargeprice_battery_range_max", 80f),
        )
        set(value) {
            sp.edit().putFloat("chargeprice_battery_range_min", value[0])
                .putFloat("chargeprice_battery_range_max", value[1])
                .apply()
        }
}