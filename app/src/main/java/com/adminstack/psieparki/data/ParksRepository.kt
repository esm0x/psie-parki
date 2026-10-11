package com.adminstack.psieparki.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

data class ParksState(
    val parks: List<Park> = emptyList(),
    /** When OSM was read for the copy on screen, "2026-10-09T01:44:35Z". */
    val osmBase: String? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** Only set when there is nothing to show; a failed refresh over good data is silent. */
    val error: String? = null,
)

/**
 * The parks, from the newest copy available: the one downloaded last, or failing that
 * the one baked into the APK at build time. There is no server: the list is a static
 * file in the GitHub repo that a weekly job rebuilds from OSM, so the app downloads it
 * at most once a day and works offline in between.
 */
class ParksRepository(context: Context, url: String = DATA_URL) {
    private val file = JsonCache(
        context, SEED, url,
        parse = { parksJson.decodeFromString(ParksFile.serializer(), it).sanitized() },
        isPlausible = { it.parks.size >= MIN_PARKS },
    )
    private val _state = MutableStateFlow(ParksState())
    val state: StateFlow<ParksState> = _state.asStateFlow()

    fun park(id: String): Park? = _state.value.parks.firstOrNull { it.id == id }

    /** Shows whatever is stored, then fetches a newer file if the stored one is a day old. */
    suspend fun load(force: Boolean = false) {
        if (_state.value.parks.isEmpty()) {
            val local = withContext(Dispatchers.IO) { file.readLocal() }
            _state.value = ParksState(parks = local?.parks.orEmpty(), osmBase = local?.osm_base, loading = local == null)
        }
        if (force || file.isStale) refresh()
        _state.value = _state.value.copy(loading = false)
    }

    private suspend fun refresh() {
        _state.value = _state.value.copy(refreshing = true)
        val result = withContext(Dispatchers.IO) { runCatching { file.download() } }
        result.onSuccess { f ->
            _state.value = ParksState(parks = f.parks, osmBase = f.osm_base, loading = false)
        }.onFailure { e ->
            _state.value = _state.value.copy(
                refreshing = false,
                error = if (_state.value.parks.isEmpty()) {
                    "Nie udało się pobrać listy wybiegów. Sprawdź połączenie z internetem."
                } else {
                    null
                },
            )
            if (e !is IOException) e.printStackTrace()
        }
    }

    companion object {
        const val DATA_URL = "https://raw.githubusercontent.com/esm0x/psie-parki/main/data/parks.json"
        const val SEED = "parks.json"
        private const val MIN_PARKS = 100
    }
}

/**
 * What survives from a file nobody vouches for: parks with coordinates on Earth and
 * unique ids, text cut to sane lengths. The scraper's contract guarantees all this for
 * the real file; this is for the file someone else might serve.
 */
fun ParksFile.sanitized(): ParksFile {
    fun String?.cut(n: Int) = this?.take(n)
    val seen = HashSet<String>()
    val clean = parks.asSequence()
        .filter { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && it.id.length <= 80 && seen.add(it.id) }
        .take(MAX_ITEMS)
        .map {
            it.copy(
                name = it.name.cut(120), locality = it.locality.cut(80), district = it.district.cut(80),
                region = it.region.cut(40), surface = it.surface.cut(80), openingHours = it.openingHours.cut(200),
                operator = it.operator.cut(120), description = it.description.cut(300), note = it.note.cut(300),
                website = it.website?.takeIf { w -> w.length <= 300 && (w.startsWith("https://") || w.startsWith("http://")) },
            )
        }
        .toList()
    return copy(parks = clean)
}

/** Far beyond any real count; a file with more is an attack on memory, not a list. */
const val MAX_ITEMS = 50_000
