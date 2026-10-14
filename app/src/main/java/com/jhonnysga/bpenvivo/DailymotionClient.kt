package com.jhonnysga.bpenvivo

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.net.UnknownHostException
import kotlin.random.Random

/**
 * Cliente Dailymotion on-device: la app pide y reproduce directo, sin
 * pasar por el PC. Replica el flujo probado del backend (canal bpenvivo,
 * player autorizado xaetl): la IP que pide el stream es la misma que lo
 * reproduce, como exige el token.
 *
 * Descubrimiento: `GET api.dailymotion.com/user/bpenvivo/videos` con el
 * campo `onair` (fuente de verdad de qué está al aire).
 *
 * Extracción (probada end-to-end):
 *  1. Sesión: `GET geo.dailymotion.com/player/xaetl.html?video={id}`
 *     con Referer beisbolplay.com → cookies `v1st` y `ts`.
 *  2. Metadata: `GET geo.dailymotion.com/video/{id}.json` con los
 *     parámetros del player autorizado (+ cookies) → `qualities.auto[0].url`.
 *
 * Llamar fuera del hilo principal.
 */
object DailymotionClient {

    /** Canal que publica los en vivo (el mismo del backend). */
    private const val CHANNEL = "bpenvivo"
    private const val API_BASE = "https://api.dailymotion.com"
    private const val GEO_BASE = "https://geo.dailymotion.com"

    /** Player autorizado y publisher de beisbolplay.com (como el backend). */
    private const val PLAYER_ID = "xaetl"
    private const val PUBLISHER_ID = "x2ae5b7"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** Referer autorizado (el que usa el backend en producción). */
    const val REFERER = "https://www.beisbolplay.com/"

    private val jar = MemoryCookieJar()

    /** Cliente de extracción con jar de cookies propio. */
    private val extractionClient: OkHttpClient by lazy {
        Net.newBuilder().cookieJar(jar).build()
    }

    /**
     * Videos del canal bpenvivo que están al aire ahora mismo,
     * según el campo `onair` de la API (igual que el backend).
     */
    @Throws(IOException::class)
    fun fetchLive(): List<StreamItem> {
        val body = try {
            Net.get("$API_BASE/user/$CHANNEL/videos?fields=id,title,onair,thumbnail_480_url&limit=20")
        } catch (e: UnknownHostException) {
            throw IOException("Sin conexión a internet o DNS bloqueado")
        }
        val list = JSONObject(body).optJSONArray("list") ?: return emptyList()
        val out = ArrayList<StreamItem>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (!o.optBoolean("onair", false)) continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val title = o.optString("title").ifBlank { id }
            out.add(
                StreamItem(
                    id = id,
                    title = title,
                    thumbnail = o.optString("thumbnail_480_url").takeIf { it.isNotBlank() },
                    channel = channelName(title),
                    logo = null
                )
            )
        }
        return out
    }

    /** Nombre del canal según el título (igual que el backend). */
    private fun channelName(title: String): String? {
        val t = title.uppercase()
        return when {
            "1BN" in t -> "1 Baseball Network Internacional"
            "BEISBOLPLAY" in t || "BP" in t -> "BEISBOLPLAY"
            else -> null
        }
    }

    /**
     * URL maestra HLS del video, con sesión válida del player autorizado.
     * Lanza IOException si no hay stream.
     */
    @Throws(IOException::class)
    fun extractStreamUrl(videoId: String): String {
        // 1. Sesión del player autorizado (deja v1st/ts en el jar).
        val sessionReq = Request.Builder()
            .url("$GEO_BASE/player/$PLAYER_ID.html?video=$videoId")
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .build()
        extractionClient.newCall(sessionReq).execute().use { resp ->
            resp.body?.close()
        }

        // 2. Metadata con los parámetros del player autorizado.
        val v1st = jar.cookieNamed("v1st") ?: ("rnd-" + randomHex(16))
        val ts = jar.cookieNamed("ts") ?: Random.nextInt(100000, 999999).toString()
        val params = listOf(
            "legacy" to "true",
            "embedder" to REFERER,
            "geo" to "1",
            "player-id" to PLAYER_ID,
            "publisher-id" to PUBLISHER_ID,
            "locale" to "es",
            "dmV1st" to v1st,
            "dmTs" to ts,
            "is_native_app" to "0",
            "dmViewId" to randomStr(20),
            "parallelCalls" to "1"
        ).joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        val metaReq = Request.Builder()
            .url("$GEO_BASE/video/$videoId.json?$params")
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Origin", REFERER)
            .build()
        extractionClient.newCall(metaReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val url = JSONObject(body)
                .optJSONObject("qualities")
                ?.optJSONArray("auto")
                ?.optJSONObject(0)
                ?.optString("url")
                .orEmpty()
                .replace("\\/", "/")
            if (url.isBlank() || ".m3u8" !in url) {
                throw IOException("No se encontró el stream del video")
            }
            return url
        }
    }

    private fun randomStr(len: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..len).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun randomHex(len: Int): String {
        val chars = "0123456789abcdef"
        return (1..len).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    /** Jar de cookies en memoria con lectura por nombre. */
    private class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                store.removeAll { it.name == c.name && it.domain == c.domain }
                store.add(c)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store.filter { it.matches(url) }

        fun cookieNamed(name: String): String? =
            store.firstOrNull { it.name == name }?.value
    }
}
