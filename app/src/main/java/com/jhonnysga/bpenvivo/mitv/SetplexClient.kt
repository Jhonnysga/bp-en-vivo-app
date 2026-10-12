package com.jhonnysga.bpenvivo.mitv

import android.os.Build
import com.jhonnysga.bpenvivo.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP de la API Setplex (Thundernet TV GO).
 *
 * Contrato verificado en vivo el 12/10/2026 desde IP de Venezuela
 * (ver `~/workspace/thundernet-tv/API.md`):
 * - Login: `POST https://app.thundernet.net.ve/wbs/web/auth`
 *   con `{email, password, manualProfileManagement: true, model}` y los
 *   headers `MW-Paid-Content-Allowed` / `MW-InApp-Subscription-Payment-Supported`.
 * - API: `https://app.thundernet.net.ve/wbs/web/api` + `/v3/...` o `/media/...`.
 * - Envoltorio: `{"status":"OK"|"ERROR","payload":{...},"errorCode":...}`.
 *
 * SUPOSICIONES (no verificables por el geoblock; documentadas, no inventadas
 * como hechos):
 * 1. El JWT de sesión viaja en `Authorization: Bearer <jwt>`.
 * 2. El token viene en el payload del login bajo `token`/`accessToken`/`jwt`/
 *    `idToken`/`authToken` (o dentro de `session`). Si el formato real difiere,
 *    [extractToken] lanza un error explícito en vez de fallar en silencio.
 * 3. `POST /v3/session/refresh` con `{}` renueva el JWT (misma extracción).
 * 4. El payload de `.../url` trae `url` y, si hay DRM, `licenseUrl` (+ headers).
 * 5. El EPG se pide con `POST /media/tv/epg` `{epgIds, period:{start,end}}`
 *    en milisegundos; la respuesta se parsea de forma tolerante.
 * 6. Las macros `STPLX_*` de la URL del stream se sustituyen con los valores
 *    que usa el cliente web oficial (device id estable, dominio, app, etc.).
 *
 * Debe llamarse fuera del hilo principal.
 */
object SetplexClient {

    const val WEB_BASE = "https://app.thundernet.net.ve"
    private const val API = "$WEB_BASE/wbs/web/api"
    private const val V3 = "$API/v3"
    private const val AUTH_URL = "$WEB_BASE/wbs/web/auth"

    class GeoblockException(message: String) : IOException(message)
    class AuthException(message: String) : IOException(message)

    private val GEO_CODES = setOf("GE001", "WB0051", "WB0052", "WG0062")
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ login

    /**
     * Inicia sesión. Devuelve el JWT. Lanza [GeoblockException] si la IP no es
     * de Venezuela, [AuthException]/[IOException] con el mensaje del servidor.
     */
    fun login(email: String, password: String, deviceModel: String): String {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("manualProfileManagement", true)
            .put("model", deviceModel)
        val req = Request.Builder()
            .url(AUTH_URL)
            .post(body.toString().toRequestBody(JSON))
            .header("MW-Paid-Content-Allowed", "true")
            .header("MW-InApp-Subscription-Payment-Supported", "true")
            .header("Accept", "application/json")
            .build()
        val (code, json) = execute(req)
        val payload = checkEnvelope(code, json)
        return extractToken(payload)
            ?: throw IOException("El servidor no devolvió token de sesión (formato inesperado)")
    }

    // ------------------------------------------------------------- llamadas

    fun getChannels(store: SessionStore): List<TvChannel> {
        val json = apiGet(store, "/v3/channels/tv")
        val out = ArrayList<TvChannel>()
        val arr = payloadArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out.add(
                TvChannel(
                    id = id,
                    name = o.optString("name").ifBlank { o.optString("title").ifBlank { id } },
                    logoUrl = o.optString("logoUrl").takeIf { it.isNotBlank() },
                    channelNumber = o.optString("channelNumber").takeIf { it.isNotBlank() },
                    categoryId = o.optString("categoryId").takeIf { it.isNotBlank() },
                    categoryName = o.optString("categoryName").takeIf { it.isNotBlank() },
                    epgId = o.optString("epgId").takeIf { it.isNotBlank() }
                )
            )
        }
        return out
    }

    fun getCategories(store: SessionStore): List<TvCategory> {
        val json = apiGet(store, "/v3/channels/tv/categories")
        val out = ArrayList<TvCategory>()
        val arr = payloadArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out.add(TvCategory(id, o.optString("name").ifBlank { o.optString("title").ifBlank { id } }))
        }
        return out
    }

    fun getChannelUrl(store: SessionStore, channelId: String): PlayableUrl {
        val json = apiGet(store, "/v3/channels/tv/$channelId/url")
        return parsePlayable(payloadObject(json)
            ?: throw IOException("El servidor no devolvió URL de reproducción"))
    }

    fun getVodUrl(store: SessionStore, vodId: String): PlayableUrl {
        val json = apiGet(store, "/v3/vods/$vodId/url")
        return parsePlayable(payloadObject(json)
            ?: throw IOException("El servidor no devolvió URL de reproducción"))
    }

    fun getEpisodeUrl(
        store: SessionStore,
        seriesId: String,
        seasonId: String,
        episodeId: String
    ): PlayableUrl {
        val json = apiGet(store, "/v3/tvshows/$seriesId/seasons/$seasonId/episodes/$episodeId/url")
        return parsePlayable(payloadObject(json)
            ?: throw IOException("El servidor no devolvió URL de reproducción"))
    }

    fun getCatchupUrl(
        store: SessionStore,
        channelId: String,
        catchupId: String,
        programmeId: String
    ): PlayableUrl {
        val json = apiGet(store, "/v3/channels/tv/$channelId/catchups/$catchupId/programmes/$programmeId/url")
        return parsePlayable(payloadObject(json)
            ?: throw IOException("El servidor no devolvió URL de reproducción"))
    }

    /** EPG "ahora/siguiente": un solo request para todos los epgIds del día. */
    fun getEpg(
        store: SessionStore,
        epgIds: List<String>,
        startMs: Long,
        endMs: Long
    ): Map<String, List<EpgProgram>> {
        if (epgIds.isEmpty()) return emptyMap()
        val body = JSONObject()
            .put("epgIds", JSONArray(epgIds))
            .put("period", JSONObject().put("start", startMs).put("end", endMs))
        val json = apiPost(store, "/media/tv/epg", body)
        return parseEpg(json)
    }

    fun getCatchupProgrammes(store: SessionStore, channelId: String): List<CatchupProgramme> {
        val json = apiGet(store, "/v3/channels/tv/catchups")
        val arr = payloadArray(json)
        // Recolecta (catchupId, canal) de todas las entradas.
        data class Entry(val catchupId: String, val channelId: String)
        val entries = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val oid = o.optString("channelId").ifBlank { o.optString("id") }
            val cs = o.optJSONArray("catchups")
            if (cs != null) {
                for (j in 0 until cs.length()) {
                    val cid = cs.optJSONObject(j)?.optString("id")
                    if (!cid.isNullOrBlank()) entries.add(Entry(cid, oid))
                }
            } else {
                o.optString("catchupId").takeIf { it.isNotBlank() }?.let {
                    entries.add(Entry(it, oid))
                }
            }
        }
        val wanted = entries.filter { it.channelId == channelId }.ifEmpty { entries }
        val out = ArrayList<CatchupProgramme>()
        for (e in wanted) {
            val pj = runCatching {
                apiGet(store, "/v3/channels/tv/$channelId/catchups/${e.catchupId}/programmes")
            }.getOrNull() ?: continue
            val parr = payloadArray(pj)
            for (i in 0 until parr.length()) {
                val o = parr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                // Evita duplicados si varias entradas traen lo mismo.
                if (out.any { it.id == id }) continue
                out.add(
                    CatchupProgramme(
                        catchupId = e.catchupId,
                        id = id,
                        title = o.optString("title").ifBlank { o.optString("name").ifBlank { id } },
                        startMs = parseTime(o, "start", "startTime", "begin") ?: 0L
                    )
                )
            }
        }
        return out.sortedByDescending { it.startMs }
    }

    fun getMovies(store: SessionStore): List<VodItem> {
        val json = apiGet(
            store, "/v3/vods",
            mapOf("count" to "60", "sort-by" to "updatedTime", "sort-order" to "desc")
        )
        return parseVodItems(json)
    }

    fun getSeries(store: SessionStore): List<VodItem> {
        val json = apiGet(
            store, "/v3/tvshows",
            mapOf("count" to "60", "sort-by" to "updatedTime", "sort-order" to "desc")
        )
        return parseVodItems(json)
    }

    fun getSeasons(store: SessionStore, seriesId: String): List<SeasonItem> {
        val json = apiGet(store, "/v3/tvshows/$seriesId/seasons")
        val out = ArrayList<SeasonItem>()
        val arr = payloadArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out.add(
                SeasonItem(
                    seriesId = seriesId,
                    id = id,
                    title = o.optString("name").ifBlank { o.optString("title").ifBlank { "Temporada" } },
                    posterUrl = firstNonBlank(o, "posterUrl", "logoUrl", "image", "poster")
                )
            )
        }
        return out
    }

    fun getEpisodes(store: SessionStore, seriesId: String, seasonId: String): List<EpisodeItem> {
        val json = apiGet(store, "/v3/tvshows/$seriesId/seasons/$seasonId/episodes")
        val out = ArrayList<EpisodeItem>()
        val arr = payloadArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out.add(
                EpisodeItem(
                    seriesId = seriesId,
                    seasonId = seasonId,
                    id = id,
                    title = o.optString("name").ifBlank { o.optString("title").ifBlank { id } },
                    posterUrl = firstNonBlank(o, "posterUrl", "logoUrl", "image", "poster")
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------- plumbing

    private fun apiGet(
        store: SessionStore,
        path: String,
        query: Map<String, String> = emptyMap()
    ): JSONObject {
        val token = store.getJwt() ?: throw AuthException("Sin sesión")
        val url = buildUrl(path, query)
        var (code, json) = execute(authReq(url, token))
        if (code == 401) {
            val newToken = refresh(store)
            val second = execute(authReq(url, newToken))
            code = second.first
            json = second.second
        }
        return checkEnvelope(code, json)
    }

    private fun apiPost(store: SessionStore, path: String, body: JSONObject): JSONObject {
        val token = store.getJwt() ?: throw AuthException("Sin sesión")
        val url = "$API$path"
        var (code, json) = execute(
            Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
        )
        if (code == 401) {
            val newToken = refresh(store)
            val second = execute(
                Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
                    .header("Authorization", "Bearer $newToken")
                    .header("Accept", "application/json")
                    .build()
            )
            code = second.first
            json = second.second
        }
        return checkEnvelope(code, json)
    }

    private fun authReq(url: String, token: String): Request =
        Request.Builder().url(url).get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

    private fun buildUrl(path: String, query: Map<String, String>): String {
        val base = "$API$path".toHttpUrlOrNull() ?: return "$API$path"
        val b = base.newBuilder()
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.build().toString()
    }

    /** Renueva el JWT; si falla, limpia la sesión y lanza [AuthException]. */
    private fun refresh(store: SessionStore): String {
        val old = store.getJwt() ?: throw AuthException("Sin sesión")
        return try {
            val req = Request.Builder()
                .url("$V3/session/refresh")
                .post("{}".toRequestBody(JSON))
                .header("Authorization", "Bearer $old")
                .header("Accept", "application/json")
                .build()
            val (code, json) = execute(req)
            val token = extractToken(checkEnvelope(code, json))
                ?: throw IOException("refresh sin token")
            store.saveJwt(token)
            token
        } catch (e: GeoblockException) {
            throw e
        } catch (e: Exception) {
            store.clearJwt()
            throw AuthException("La sesión venció, inicia sesión de nuevo")
        }
    }

    private fun execute(req: Request): Pair<Int, JSONObject?> {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            return resp.code to json
        }
    }

    /** Valida el envoltorio `{status,payload,errorCode,message}` de Setplex. */
    private fun checkEnvelope(code: Int, json: JSONObject?): JSONObject {
        if (json != null) {
            val status = json.optString("status")
            if (status.equals("ERROR", ignoreCase = true)) {
                val errCode = json.optString("errorCode")
                if (errCode in GEO_CODES) {
                    throw GeoblockException(
                        "Este servicio solo está disponible en Venezuela (restricción del proveedor)."
                    )
                }
                val msg = json.optString("message").ifBlank { errCode }
                throw IOException(if (msg.isNotBlank()) msg else "Error del servidor")
            }
            if (status.equals("OK", ignoreCase = true)) return json
        }
        if (code == 401) throw AuthException("Sesión inválida o vencida")
        if (code !in 200..299) throw IOException("Error del servidor (HTTP $code)")
        throw IOException("Respuesta inesperada del servidor")
    }

    private fun payloadObject(json: JSONObject): JSONObject? =
        json.opt("payload") as? JSONObject

    private fun payloadArray(json: JSONObject): JSONArray {
        val p = json.opt("payload") ?: return JSONArray()
        if (p is JSONArray) return p
        if (p is JSONObject) {
            for (k in listOf("content", "channels", "items", "data", "results", "list")) {
                (p.opt(k) as? JSONArray)?.let { return it }
            }
        }
        return JSONArray()
    }

    private fun extractToken(payload: JSONObject): String? {
        val keys = listOf("token", "accessToken", "jwt", "idToken", "authToken")
        for (k in keys) payload.optString(k).takeIf { it.isNotBlank() }?.let { return it }
        val session = payload.optJSONObject("session")
        if (session != null) {
            for (k in keys) session.optString(k).takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun parsePlayable(payload: JSONObject): PlayableUrl {
        val url = payload.optString("url").ifBlank { null }
            ?: throw IOException("El servidor no devolvió URL de reproducción")
        val license = listOf("licenseUrl", "drmLicenseUrl", "license", "drmUrl")
            .firstNotNullOfOrNull { k -> payload.optString(k).takeIf { it.isNotBlank() } }
            ?: payload.optJSONObject("drm")?.let { d ->
                listOf("licenseUrl", "url").firstNotNullOfOrNull { k ->
                    d.optString(k).takeIf { it.isNotBlank() }
                }
            }
        val headers = mutableMapOf<String, String>()
        val hObj = payload.optJSONObject("licenseHeaders")
            ?: payload.optJSONObject("drmHeaders")
            ?: payload.optJSONObject("headers")
            ?: payload.optJSONObject("drm")?.optJSONObject("headers")
        if (hObj != null) {
            val keys = hObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                headers[k] = hObj.optString(k)
            }
        }
        return PlayableUrl(url, license, headers)
    }

    private fun parseVodItems(json: JSONObject): List<VodItem> {
        val out = ArrayList<VodItem>()
        val arr = payloadArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out.add(
                VodItem(
                    id = id,
                    title = o.optString("name").ifBlank { o.optString("title").ifBlank { id } },
                    posterUrl = firstNonBlank(o, "posterUrl", "logoUrl", "image", "poster")
                )
            )
        }
        return out
    }

    private fun parseEpg(json: JSONObject): Map<String, List<EpgProgram>> {
        val out = mutableMapOf<String, MutableList<EpgProgram>>()
        val arr = payloadArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val progs = o.optJSONArray("programmes") ?: o.optJSONArray("programs")
            if (progs != null) {
                val id = o.optString("epgId").ifBlank { o.optString("id") }
                for (j in 0 until progs.length()) {
                    parseProgram(progs.optJSONObject(j))?.let {
                        out.getOrPut(id) { mutableListOf() }.add(it)
                    }
                }
            } else {
                val id = o.optString("epgId")
                parseProgram(o)?.let { out.getOrPut(id) { mutableListOf() }.add(it) }
            }
        }
        return out
    }

    private fun parseProgram(o: JSONObject?): EpgProgram? {
        if (o == null) return null
        val title = o.optString("title")
            .ifBlank { o.optString("name") }
            .ifBlank { o.optString("programTitle") }
        if (title.isBlank()) return null
        val start = parseTime(o, "start", "startTime", "begin", "startDate") ?: return null
        val end = parseTime(o, "end", "endTime", "finish", "endDate") ?: return null
        return EpgProgram(title, start, end)
    }

    private fun parseTime(o: JSONObject, vararg keys: String): Long? {
        for (k in keys) {
            if (o.isNull(k)) continue
            when (val v = o.opt(k)) {
                is Number -> {
                    val n = v.toLong()
                    return when {
                        n > 1_000_000_000_000L -> n
                        n > 1_000_000_000L -> n * 1000
                        else -> null
                    }
                }
                is String -> {
                    if (v.isBlank()) continue
                    v.toLongOrNull()?.let { n ->
                        return when {
                            n > 1_000_000_000_000L -> n
                            n > 1_000_000_000L -> n * 1000
                            else -> null
                        }
                    }
                    parseIso(v)?.let { return it }
                }
            }
        }
        return null
    }

    /** ISO-8601 sin java.time (minSdk 24). */
    private fun parseIso(v: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return f.parse(v)?.time
            } catch (_: Exception) {
                // siguiente patrón
            }
        }
        return null
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String? {
        for (k in keys) o.optString(k).takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /**
     * Reescribe las macros `STPLX_*` (también en forma `[STPLX_*]`) de la URL
     * del stream, como hace el cliente web oficial.
     */
    fun rewriteMacros(raw: String, store: SessionStore): String {
        var u = raw
        val subs = mapOf(
            "STPLX_DEVICE_ID" to store.deviceUuid(),
            "STPLX_DOMAIN" to "app.thundernet.net.ve",
            "STPLX_APP_NAME" to "MiTV",
            "STPLX_ANM" to "MiTV",
            "STPLX_APP_VERSION" to BuildConfig.VERSION_NAME,
            "STPLX_AVER" to BuildConfig.VERSION_NAME,
            "STPLX_DEVICE_TYPE" to "ANDROID",
            "STPLX_DT" to "ANDROID",
            "STPLX_OS" to "Android ${Build.VERSION.RELEASE}",
            "STPLX_DEVICE_MODEL" to Build.MODEL,
            "STPLX_DEVICE_MANUFACTURER" to (Build.MANUFACTURER ?: ""),
            "STPLX_AD_ID" to "",
            "STPLX_UPAL" to "",
            "STPLX_SESSION_CONTENT_UUID" to UUID.randomUUID().toString(),
            "STPLX_PLAYER_H" to "",
            "STPLX_PLAYER_W" to "",
            "STPLX_TM" to System.currentTimeMillis().toString()
        )
        for ((k, v) in subs) {
            u = u.replace("[$k]", v).replace(k, v)
        }
        return u
    }
}
