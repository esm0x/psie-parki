package com.adminstack.psieparki.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One data file from the repo: the last good download in filesDir, or failing that the
 * copy baked into the APK. Shared by the parks and the vets.
 *
 * Everything that comes over the network is treated as hostile until parsed: a size
 * cap, a parse that must succeed, and a minimum count, so a truncated, emptied or
 * absurd file never replaces a good one.
 */
class JsonCache<T>(
    private val context: Context,
    private val fileName: String,
    private val url: String,
    private val parse: (String) -> T,
    private val isPlausible: (T) -> Boolean,
    private val client: OkHttpClient = defaultClient,
) {
    private val cache = File(context.filesDir, fileName)

    val isStale: Boolean
        get() = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS

    /** Cached copy if it parses, else the seed from assets, else null. Call off the main thread. */
    fun readLocal(): T? {
        val fromCache = runCatching { if (cache.exists()) parse(cache.readText()) else null }.getOrNull()
        if (fromCache != null && isPlausible(fromCache)) return fromCache
        return runCatching {
            context.assets.open(fileName).bufferedReader().use { parse(it.readText()) }
        }.getOrNull()
    }

    /** Downloads, checks and stores a fresh copy. Call off the main thread. */
    fun download(): T {
        val request = Request.Builder().url(url).header("User-Agent", "PsieParki-Android").build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val b = response.body ?: throw IOException("empty body")
            if (b.contentLength() > MAX_BYTES) throw IOException("file too large")
            // contentLength is -1 for chunked responses: read with a hard cap instead.
            val bytes = b.byteStream().use { readCapped(it, MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) throw IOException("file too large")
            String(bytes, Charsets.UTF_8)
        }
        val parsed = try {
            parse(body)
        } catch (e: Exception) {
            throw IOException("unreadable $fileName", e)
        }
        if (!isPlausible(parsed)) throw IOException("implausible $fileName")
        val tmp = File(cache.parentFile, "$fileName.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(cache)) {
            cache.delete()
            tmp.renameTo(cache)
        }
        return parsed
    }

    /** InputStream.readNBytes is Java 11, which Android has only from API 33. */
    private fun readCapped(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_AGE_MS = 24 * 3600 * 1000L
        /** Ten times today's largest file; anything bigger is not ours. */
        const val MAX_BYTES = 8 * 1024 * 1024

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
