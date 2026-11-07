package com.trendgif.keyboard

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// KLIPY: built by the original Tenor team, free key, trending + search like Tenor had.
object KlipyClient {
    private const val ROOT = "https://api.klipy.com/api/v1"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun trending(key: String, offset: Int, limit: Int = 30): List<GifItem> =
        fetch("$ROOT/${enc(key)}/gifs/trending?page=${offset / limit + 1}&per_page=$limit")

    fun search(key: String, q: String, offset: Int, limit: Int = 30): List<GifItem> =
        fetch("$ROOT/${enc(key)}/gifs/search?q=${enc(q)}&page=${offset / limit + 1}&per_page=$limit&content_filter=medium")

    private fun fileUrl(file: JSONObject, size: String, fmt: String): String =
        file.optJSONObject(size)?.optJSONObject(fmt)?.optString("url").orEmpty()

    private fun fileSize(file: JSONObject, size: String, fmt: String): Long =
        file.optJSONObject(size)?.optJSONObject(fmt)?.optLong("size", Long.MAX_VALUE) ?: Long.MAX_VALUE

    private fun firstUrl(file: JSONObject, sizes: List<String>, fmt: String): String =
        sizes.map { fileUrl(file, it, fmt) }.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun fetch(url: String): List<GifItem> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            if (!root.optBoolean("result", true)) throw IOException("KLIPY rejected the request")
            val arr = root.getJSONObject("data").getJSONArray("data")
            val out = ArrayList<GifItem>()
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val file = g.optJSONObject("file") ?: continue // skips ad entries
                val slug = g.optString("slug").ifBlank { g.optString("id") }
                if (slug.isBlank()) continue

                val preview = firstUrl(file, listOf("sm", "md", "xs", "hd"), "gif")
                val gif = firstUrl(file, listOf("md", "sm", "hd", "xs"), "gif")

                // sharper HD MP4 when it's small enough, otherwise the medium one
                val hdMp4 = fileUrl(file, "hd", "mp4")
                val mp4 = if (hdMp4.isNotBlank() && fileSize(file, "hd", "mp4") <= 2_500_000L) hdMp4
                else firstUrl(file, listOf("md", "sm", "hd"), "mp4")

                if (preview.isBlank() || gif.isBlank()) continue
                out.add(GifItem(slug.replace(Regex("[^A-Za-z0-9_-]"), "_"), preview, mp4.ifBlank { null }, gif))
            }
            return out
        } finally {
            c.disconnect()
        }
    }
}
