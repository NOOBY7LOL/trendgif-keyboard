package com.trendgif.keyboard

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class GifItem(
    val id: String,
    val previewUrl: String,
    val mp4Url: String?,
    val gifUrl: String,
    val ts: Long = 0L // upload time in ms (0 = unknown)
)

// rawCount = how many results the server sent (used for paging), items = after the "recent only" filter
data class GifPage(val items: List<GifItem>, val rawCount: Int)

object GiphyClient {
    private const val BASE = "https://api.giphy.com/v1/gifs"

    fun trending(key: String, offset: Int, maxAgeDays: Int, limit: Int = 50): GifPage =
        fetch("$BASE/trending?api_key=$key&limit=$limit&offset=$offset&rating=pg-13&bundle=messaging_non_clips", maxAgeDays)

    fun search(key: String, q: String, offset: Int, maxAgeDays: Int, limit: Int = 50): GifPage =
        fetch(
            "$BASE/search?api_key=$key&q=${URLEncoder.encode(q, "UTF-8")}" +
                "&limit=$limit&offset=$offset&rating=pg-13&lang=en&bundle=messaging_non_clips",
            maxAgeDays
        )

    private fun fetch(url: String, maxAgeDays: Int): GifPage {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(body).getJSONArray("data")

            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val cutoff = if (maxAgeDays > 0) System.currentTimeMillis() - maxAgeDays * 86_400_000L else 0L

            val out = ArrayList<GifItem>()
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val im = g.optJSONObject("images") ?: continue

                // skip old GIFs: Giphy tells us when each one was uploaded
                val ts = try {
                    fmt.parse(g.optString("import_datetime"))?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
                if (cutoff > 0 && ts > 0 && ts < cutoff) continue

                val orig = im.optJSONObject("original")
                val fh = im.optJSONObject("fixed_height")

                val preview = im.optJSONObject("fixed_width_downsampled")?.optString("url").orEmpty()
                    .ifBlank { im.optJSONObject("fixed_width_small")?.optString("url").orEmpty() }
                val gif = fh?.optString("url").orEmpty()

                val origSize = orig?.optString("mp4_size")?.toLongOrNull() ?: Long.MAX_VALUE
                val mp4 = (if (origSize <= 2_500_000L) orig?.optString("mp4") else null).orEmpty()
                    .ifBlank { fh?.optString("mp4").orEmpty() }

                if (preview.isBlank() || gif.isBlank()) continue
                out.add(GifItem(g.getString("id"), preview, mp4.ifBlank { null }, gif, ts))
            }
            out.sortByDescending { it.ts } // newest first
            return GifPage(out, arr.length())
        } finally {
            c.disconnect()
        }
    }

    fun download(url: String, dest: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val tmp = File(dest.path + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) throw IOException("rename failed")
        } finally {
            c.disconnect()
        }
    }
}
