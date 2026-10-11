package com.adminstack.psieparki.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** A vet or an animal shelter, from data/vets.json (scraper/CONTRACT.md). */
@Serializable
data class Helper(
    val id: String,
    /** "weterynarz" or "schronisko". */
    val kind: String,
    val name: String? = null,
    val lat: Double,
    val lon: Double,
    val locality: String? = null,
    val district: String? = null,
    val region: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val openingHours: String? = null,
    val allDay: Boolean = false,
) {
    val isVet: Boolean get() = kind == "weterynarz"

    val title: String get() = name ?: if (isVet) "Gabinet weterynaryjny" else "Schronisko dla zwierząt"

    val place: String?
        get() = listOfNotNull(address, locality).joinToString(", ").ifEmpty { null }

    /** Parsed once; null when OSM has no hours or hours this app will not guess at. */
    @Transient
    val hours: OpeningHours? = if (allDay) OpeningHours.parse("24/7") else OpeningHours.parse(openingHours)

    /** "tel:+48601234567": what the dialer wants, spaces and dashes removed. */
    val dialUri: String? get() = phone?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 9 }?.let { "tel:$it" }
}

@Serializable
data class HelpersFile(
    val generated_at: String? = null,
    val osm_base: String? = null,
    val count: Int = 0,
    val places: List<Helper> = emptyList(),
)

data class HelpersState(
    val places: List<Helper> = emptyList(),
    val loading: Boolean = true,
)

/** Vets and shelters, cached exactly like the parks (see [JsonCache]). */
class HelpersRepository(context: Context, url: String = DATA_URL) {
    private val file = JsonCache(
        context, SEED, url,
        parse = { parksJson.decodeFromString(HelpersFile.serializer(), it).sanitized() },
        isPlausible = { it.places.size >= MIN_PLACES },
    )
    private val _state = MutableStateFlow(HelpersState())
    val state: StateFlow<HelpersState> = _state.asStateFlow()

    suspend fun load() {
        if (_state.value.places.isEmpty()) {
            val local = withContext(Dispatchers.IO) { file.readLocal() }
            _state.value = HelpersState(local?.places.orEmpty(), loading = local == null)
        }
        if (file.isStale) {
            withContext(Dispatchers.IO) { runCatching { file.download() } }.onSuccess {
                _state.value = HelpersState(it.places, loading = false)
            }
        }
        _state.value = _state.value.copy(loading = false)
    }

    companion object {
        const val DATA_URL = "https://raw.githubusercontent.com/esm0x/psie-parki/main/data/vets.json"
        const val SEED = "vets.json"
        private const val MIN_PLACES = 500
    }
}

fun HelpersFile.sanitized(): HelpersFile {
    fun String?.cut(n: Int) = this?.take(n)
    val seen = HashSet<String>()
    return copy(places = places.asSequence()
        .filter { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && it.id.length <= 80 && seen.add(it.id) }
        .take(MAX_ITEMS)
        .map {
            it.copy(
                name = it.name.cut(120), locality = it.locality.cut(80), district = it.district.cut(80),
                address = it.address.cut(120), phone = it.phone.cut(30), openingHours = it.openingHours.cut(200),
                website = it.website?.takeIf { w -> w.length <= 300 && (w.startsWith("https://") || w.startsWith("http://")) },
            )
        }
        .toList())
}

enum class HelperFilter(val label: String) { OPEN_NOW("Otwarte teraz"), ALL_DAY("Całodobowe") }
