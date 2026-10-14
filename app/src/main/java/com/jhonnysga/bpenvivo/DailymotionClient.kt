package com.jhonnysga.bpenvivo

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente Dailymotion on-device: la app pide y reproduce directo, sin
 * pasar por el PC. El token del stream queda atado a la IP del propio
 * teléfono/Fire TV, que es la que también reproduce.
 *
 * Dos operaciones (llamar fuera del hilo principal):
 *  - fetchLive(): videos en vivo del canal BeisbolPlay.
 *  - extractStreamUrl(videoId): URL maestra HLS (.m3u8) del video.
 */
object DailymotionClient {

    /** Canal oficial de BeisbolPlay en Dailymotion. */
    private const val CHANNEL = "beisbolplay"
    private const val API_BASE = "https://api.dailymotion.com"
    private const val METADATA_URL = "https://www.dailymotion.com/player/metadata/video/"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /**
     * Referer enviado al pedir el .m3u8 y los segmentos. Si un juego no
     * abre, este es el primer valor a ajustar.
     */
    const val REFERER = "https://www.dailymotion.com/"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * Lista los videos marcados en vivo por la API y confirma uno por uno
     * contra el metadata (la API a veces marca en vivo canales apagados).
     * Solo los que traen `qualities` están transmitiendo de verdad.
     */
    fun fetchLive(): List<StreamItem> {
        val url = "$API_BASE/user/$CHANNEL/videos" +
            "?live=true&fields=id,title,thumbnail_480_url&limit=20"
        val body = httpGet(url)
        val list = JSONObject(body).optJSONArray("list") ?: return emptyList()
        val out = ArrayList<StreamItem>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            // Confirmar que realmente transmite antes de mostrarlo.
            if (!isActuallyLive(id)) continue
            out.add(
                StreamItem(
                    id = id,
                    title = o.optString("title").ifBlank { id },
                    thumbnail = o.optString("thumbnail_480_url").takeIf { it.isNotBlank() },
                    channel = "BeisbolPlay",
                    logo = null
                )
            )
        }
        return out
    }

    /** true si el metadata del video trae URLs de stream (está al aire). */
    private fun isActuallyLive(videoId: String): Boolean {
        return try {
            extractStreamUrl(videoId).isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Extrae la URL maestra HLS del video desde el metadata del player.
     * Estrategia defensiva (el formato puede variar):
     *  1. qualities.auto -> primer .m3u8
     *  2. mayor calidad numérica con .m3u8
     *  3. barrido con regex sobre el JSON crudo
     *
     * Lanza IOException si el canal está apagado o no hay stream.
     */
    @Throws(IOException::class)
    fun extractStreamUrl(videoId: String): String {
        val body = httpGet("$METADATA_URL$videoId")
        val json = JSONObject(body)

        json.optJSONObject("error")?.let {
            throw IOException(it.optString("title").ifBlank { "Transmisión no disponible" })
        }

        val qualities = json.optJSONObject("qualities")
        if (qualities != null) {
            // 1. auto (suele ser el master m3u8)
            firstM3u8(qualities.optJSONArray("auto"))?.let { return it }
            // 2. mayor calidad numérica disponible
            val numericKeys = qualities.keys().asSequence()
                .mapNotNull { it.toIntOrNull() }
                .sortedDescending()
            for (k in numericKeys) {
                firstM3u8(qualities.optJSONArray(k.toString()))?.let { return it }
            }
        }

        // 3. último recurso: cualquier .m3u8 en el JSON
        Regex("""https?:\\?/\\?/[^"'\s\\]*\.m3u8[^"'\s\\]*""")
            .find(body)?.value
            ?.replace("\\/", "/")
            ?.let { return it }

        throw IOException("No se encontró el stream del video")
    }

    /** Primera URL .m3u8 dentro de un arreglo de calidades. */
    private fun firstM3u8(arr: org.json.JSONArray?): String? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val url = arr.optJSONObject(i)?.optString("url").orEmpty()
            if (url.contains(".m3u8")) return url.replace("\\/", "/")
        }
        return null
    }

    @Throws(IOException::class)
    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", REFERER)
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code")
            return body
        } finally {
            conn.disconnect()
        }
    }
}
