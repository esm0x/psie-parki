package com.adminstack.psieparki.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What a person can tell us about a park. Names match worker/src/submission.ts. */
enum class SubmissionType(val wire: String) { NEW("nowy"), FEATURES("cechy"), CONFIRM("potwierdzam"), GONE("nie_istnieje") }

data class Submission(
    val type: SubmissionType,
    val parkId: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val name: String? = null,
    val features: Map<Feature, Boolean> = emptyMap(),
    val comment: String? = null,
)

/** A submission this phone sent, kept so "Moje zgłoszenia" can show what became of it. */
@Serializable
data class SentSubmission(
    val nr: Int,
    val title: String,
    val sentOn: String,
    /** "oczekuje", "przyjete", "niepotwierdzone", "odrzucone". */
    val status: String = "oczekuje",
    val reason: String? = null,
)

sealed interface SendResult {
    data class Sent(val nr: Int) : SendResult
    /** Refused by validation or moderation; [message] is the server's, for the person. */
    data class Refused(val message: String) : SendResult
    data class Failed(val message: String) : SendResult
}

/** Wire names of the features, as in data/parks.json. */
val Feature.wire: String
    get() = when (this) {
        Feature.FENCED -> "fenced"
        Feature.LIT -> "lit"
        Feature.WATER -> "water"
        Feature.AGILITY -> "agility"
        Feature.SMALL_DOGS -> "smallDogArea"
    }

/**
 * Sends submissions to the worker and remembers them. Without a configured worker
 * ([endpoint] blank, as in a build without -PzgloszeniaUrl), [enabled] is false and the
 * app falls back to pre-filled e-mails.
 */
class Submissions(
    context: Context,
    private val endpoint: String,
    private val appVersion: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS) // moderation by the model takes a few seconds
        .build(),
) {
    private val prefs = context.getSharedPreferences("psie_parki_zgloszenia", Context.MODE_PRIVATE)
    private val _sent = MutableStateFlow(loadSent())
    val sent: StateFlow<List<SentSubmission>> = _sent.asStateFlow()

    val enabled: Boolean get() = endpoint.isNotBlank()

    /** Random, made once per install, never tied to a person. The worker hashes it. */
    val installId: String
        get() = prefs.getString(KEY_INSTALL, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_INSTALL, it) }
        }

    /** Parks this phone already confirmed or voted on, so it does not ask again. */
    fun answered(parkId: String): Set<String> =
        prefs.getStringSet(KEY_ANSWERED + parkId, emptySet()).orEmpty()

    private fun markAnswered(parkId: String, what: String) = prefs.edit {
        putStringSet(KEY_ANSWERED + parkId, answered(parkId) + what)
    }

    suspend fun send(s: Submission, title: String): SendResult = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext SendResult.Failed("Wysyłanie zgłoszeń nie jest skonfigurowane.")
        val body = buildJsonObject {
            put("typ", s.type.wire)
            s.parkId?.let { put("parkId", it) }
            s.lat?.let { put("lat", it) }
            s.lon?.let { put("lon", it) }
            s.name?.takeIf { it.isNotBlank() }?.let { put("nazwa", it.trim()) }
            s.comment?.takeIf { it.isNotBlank() }?.let { put("komentarz", it.trim()) }
            put("cechy", JsonObject(s.features.map { (f, v) -> f.wire to JsonPrimitive(v) }.toMap()))
            put("instalacja", installId)
            put("wersja", appVersion)
        }
        val request = Request.Builder()
            .url(endpoint.trimEnd('/') + "/zgloszenie")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val json = runCatching { parksJson.parseToJsonElement(text).jsonObject }.getOrNull()
                when {
                    r.code == 201 -> {
                        val nr = json?.get("nr")?.jsonPrimitive?.content?.toIntOrNull()
                            ?: return@use SendResult.Failed("Nieczytelna odpowiedź serwera.")
                        remember(SentSubmission(nr, title, LocalDate.now().toString()))
                        s.parkId?.let { pid ->
                            when (s.type) {
                                SubmissionType.CONFIRM, SubmissionType.GONE -> markAnswered(pid, ANSWER_EXISTS)
                                SubmissionType.FEATURES -> s.features.keys.forEach { markAnswered(pid, it.wire) }
                                SubmissionType.NEW -> Unit
                            }
                        }
                        SendResult.Sent(nr)
                    }
                    r.code == 422 || r.code == 429 || r.code == 413 ->
                        SendResult.Refused(json?.get("error")?.jsonPrimitive?.content ?: "Zgłoszenie nie zostało przyjęte.")
                    else -> SendResult.Failed("Serwer nie odpowiada (${r.code}). Spróbuj za chwilę.")
                }
            }
        } catch (e: IOException) {
            SendResult.Failed("Brak połączenia z internetem.")
        }
    }

    /** Asks the worker what became of every submission still waiting for a decision. */
    suspend fun refreshStatuses() = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        val updated = _sent.value.map { s ->
            if (s.status != "oczekuje") return@map s
            runCatching {
                val request = Request.Builder().url(endpoint.trimEnd('/') + "/zgloszenie/${s.nr}").build()
                client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) return@use s
                    val json = parksJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                    s.copy(
                        status = json["status"]?.jsonPrimitive?.content ?: s.status,
                        reason = json["powod"]?.takeIf { it is JsonPrimitive && it.isString }?.jsonPrimitive?.content,
                    )
                }
            }.getOrDefault(s)
        }
        save(updated)
    }

    private fun remember(s: SentSubmission) = save(listOf(s) + _sent.value.take(49))

    private fun save(list: List<SentSubmission>) {
        _sent.value = list
        prefs.edit { putString(KEY_SENT, parksJson.encodeToString(ListSerializer(SentSubmission.serializer()), list)) }
    }

    private fun loadSent(): List<SentSubmission> = runCatching {
        parksJson.decodeFromString(ListSerializer(SentSubmission.serializer()), prefs.getString(KEY_SENT, "[]")!!)
    }.getOrDefault(emptyList())

    companion object {
        /** Marker in [answered] for "this phone already said whether the park exists". */
        const val ANSWER_EXISTS = "istnieje"
        private const val KEY_INSTALL = "install_id"
        private const val KEY_SENT = "sent"
        private const val KEY_ANSWERED = "answered:"
    }
}

/** "przyjete" → what the person reads. */
fun statusLabel(status: String): String = when (status) {
    "przyjete" -> "Przyjęte"
    "niepotwierdzone" -> "Na mapie, czeka na potwierdzenie"
    "odrzucone" -> "Nie przyjęte"
    else -> "Czeka na sprawdzenie"
}

/** The "powod:…" label the moderation put on a refused submission. */
fun reasonLabel(reason: String?): String? = when (reason) {
    null -> null
    "miejsce" -> "W tym miejscu nie może być wybiegu (woda, budynek, droga). Spróbuj dokładniej."
    "limit" -> "Masz już trzy wybiegi czekające na potwierdzenie."
    "nieznany" -> "Tego wybiegu nie ma już na mapie."
    else -> "Zgłoszenie nie spełniło zasad."
}
