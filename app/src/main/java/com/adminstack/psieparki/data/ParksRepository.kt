package com.adminstack.psieparki.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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
class ParksRepository(
    private val context: Context,
    private val url: String = DATA_URL,
    private val client: OkHttpClient = defaultClient,
) {
    private val cache = File(context.filesDir, "parks.json")
    private val _state = MutableStateFlow(ParksState())
    val state: StateFlow<ParksState> = _state.asStateFlow()

    fun park(id: String): Park? = _state.value.parks.firstOrNull { it.id == id }

    /** Shows whatever is stored, then fetches a newer file if the stored one is a day old. */
    suspend fun load(force: Boolean = false) {
        if (_state.value.parks.isEmpty()) {
            val local = withContext(Dispatchers.IO) { readLocal() }
            _state.value = ParksState(
                parks = local?.parks.orEmpty(),
                osmBase = local?.osm_base,
                loading = local == null,
            )
        }
        val stale = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS
        if (force || stale) refresh()
        _state.value = _state.value.copy(loading = false)
    }

    private suspend fun refresh() {
        _state.value = _state.value.copy(refreshing = true)
        val result = withContext(Dispatchers.IO) { runCatching { download() } }
        result.onSuccess { file ->
            _state.value = ParksState(parks = file.parks, osmBase = file.osm_base, loading = false)
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

    private fun download(): ParksFile {
        val request = Request.Builder().url(url).header("User-Agent", "PsieParki-Android").build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("empty body")
        }
        val file = parksJson.decodeFromString(ParksFile.serializer(), body)
        // A truncated or emptied file must not wipe a good cache: the scraper refuses to
        // write one, but a proxy or a half-finished push could still serve it.
        if (file.parks.size < MIN_PARKS) throw IOException("only ${file.parks.size} parks")
        val tmp = File(cache.parentFile, "parks.json.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(cache)) {
            cache.delete()
            tmp.renameTo(cache)
        }
        return file
    }

    private fun readLocal(): ParksFile? {
        val fromCache = runCatching {
            if (cache.exists()) parksJson.decodeFromString(ParksFile.serializer(), cache.readText()) else null
        }.getOrNull()
        if (fromCache != null && fromCache.parks.isNotEmpty()) return fromCache
        return runCatching {
            context.assets.open(SEED).bufferedReader().use {
                parksJson.decodeFromString(ParksFile.serializer(), it.readText())
            }
        }.getOrNull()
    }

    companion object {
        const val DATA_URL = "https://raw.githubusercontent.com/esm0x/psie-parki/main/data/parks.json"
        const val SEED = "parks.json"
        private const val MAX_AGE_MS = 24 * 3600 * 1000L
        private const val MIN_PARKS = 100

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
