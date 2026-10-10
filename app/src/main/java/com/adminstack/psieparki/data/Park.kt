package com.adminstack.psieparki.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One dog park, as data/parks.json carries it. Field names are the wire contract in
 * scraper/CONTRACT.md: renaming one here empties that field in the app.
 *
 * The features are three-valued on purpose. OSM mappers record a fence when they see
 * one, but nobody maps "no water tap", so null means "nobody knows", not "no", and the
 * screen says so.
 */
@Serializable
data class Park(
    /** "node/5655915525", "way/808455741" or "manual/<slug>". */
    val id: String,
    val name: String? = null,
    val lat: Double,
    val lon: Double,
    val locality: String? = null,
    val district: String? = null,
    val region: String? = null,
    val areaM2: Int? = null,
    val fenced: Boolean? = null,
    val lit: Boolean? = null,
    val water: Boolean? = null,
    val agility: Boolean? = null,
    val smallDogArea: Boolean? = null,
    val benches: Boolean? = null,
    val bins: Boolean? = null,
    val surface: String? = null,
    val openingHours: String? = null,
    val operator: String? = null,
    val website: String? = null,
    val description: String? = null,
    val note: String? = null,
    val customersOnly: Boolean = false,
    val source: String = "osm",
) {
    val isFromOsm: Boolean get() = source == "osm"

    /** Where the park is on osm.org, for "edytuj w OpenStreetMap". */
    val osmUrl: String? get() = if (isFromOsm) "https://www.openstreetmap.org/$id" else null

    /** "Rybnik, Maroko-Nowiny", "Lubomia", or null when OSM has no admin area for it. */
    val place: String?
        get() = listOfNotNull(locality, district).joinToString(", ").ifEmpty { null }

    /** The heading. Most parks in OSM have no name, and [place] always goes under it. */
    val title: String
        get() = name ?: "Wybieg dla psów"
}

@Serializable
data class ParksFile(
    val generated_at: String? = null,
    /** When OSM was last read: the age of the data, not of the file. */
    val osm_base: String? = null,
    val count: Int = 0,
    val parks: List<Park> = emptyList(),
)

val parksJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}
