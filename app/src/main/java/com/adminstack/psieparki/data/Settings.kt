package com.adminstack.psieparki.data

import android.content.Context
import androidx.core.content.edit

/**
 * The little the app remembers: the place picked instead of sharing a location, and
 * the filters. Plain SharedPreferences: two values do not need DataStore.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("psie_parki", Context.MODE_PRIVATE)

    var pickedPlace: Locality?
        get() {
            val label = prefs.getString(KEY_PLACE, null) ?: return null
            val lat = prefs.getFloat(KEY_LAT, Float.NaN).toDouble()
            val lon = prefs.getFloat(KEY_LON, Float.NaN).toDouble()
            if (lat.isNaN() || lon.isNaN()) return null
            return Locality(label, LatLon(lat, lon), 0)
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_PLACE); remove(KEY_LAT); remove(KEY_LON)
            } else {
                putString(KEY_PLACE, value.label)
                putFloat(KEY_LAT, value.center.lat.toFloat())
                putFloat(KEY_LON, value.center.lon.toFloat())
            }
        }

    var features: Set<Feature>
        get() = prefs.getStringSet(KEY_FEATURES, emptySet()).orEmpty()
            .mapNotNull { name -> Feature.entries.firstOrNull { it.name == name } }
            .toSet()
        set(value) = prefs.edit { putStringSet(KEY_FEATURES, value.map { it.name }.toSet()) }

    /** The "Psie Parki tworzą psiarze" card on the list, once closed, stays closed. */
    var inviteDismissed: Boolean
        get() = prefs.getBoolean(KEY_INVITE, false)
        set(value) = prefs.edit { putBoolean(KEY_INVITE, value) }

    private companion object {
        const val KEY_INVITE = "invite_dismissed"
        const val KEY_PLACE = "place"
        const val KEY_LAT = "place_lat"
        const val KEY_LON = "place_lon"
        const val KEY_FEATURES = "features"
    }
}
