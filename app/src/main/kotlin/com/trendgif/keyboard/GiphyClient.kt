package com.trendgif.keyboard

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GifItem(
    val id: String,
    val previewUrl: String,
    val mp4Url: String?,
    val gifUrl: String
)

object GiphyClient {
    private const val BASE = "https://api.giphy.com/v1/gifs"

    // "Trending" = what people are sharing right now (updates through the day)
    fun trending(key: String, offset: Int, limit: Int = 30): List<GifItem> =
        fetch("$BASE/trending?api_key=$key&limit=$limit&offset=$offset&rating=pg-13&bundle=messaging_non_clips")

    fun search(key: String, q: String, offset: Int, limit: Int = 30): List<GifItem> =
        fetch(
            "$BASE/search?api_key=$key&q=${URLEncoder.encode(q, "UTF-8")}" +
                "&limit=$limit&offset=$offset&rating=pg-13&lang=en&bundle=messaging_non_clips"
        )

    private fun fetch(url: String): List<GifItem> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(body).getJSONArray("data")
            val out = ArrayList<GifItem>()
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val im = g.optJSONObject("images") ?: continue
                val orig = im.optJSONObject("original")
                val fh = im.optJSONObject("fixed_height")

                val preview = im.optJSONObject("fixed_width_downsampled")?.optString("url").orEmpty()
                    .ifBlank { im.optJSONObject("fixed_width_small")?.optString("url").orEmpty() }
                val gif = fh?.optString("url").orEmpty()

                // Prefer the sharper MP4 if it is small enough, else the 200px-high one
                val origSize = orig?.optString("mp4_size")?.toLongOrNull() ?: Long.MAX_VALUE
                val mp4 = (if (origSize <= 2_500_000L) orig?.optString("mp4") else null).orEmpty()
                    .ifBlank { fh?.optString("mp4").orEmpty() }

                if (preview.isBlank() || gif.isBlank()) continue
                out.add(GifItem(g.getString("id"), preview, mp4.ifBlank { null }, gif))
            }
            return out
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
