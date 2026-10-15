package com.jhonnysga.bpenvivo

import android.content.Context
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Cliente Thundernet TV GO on-device (plataforma Setplex Nora).
 *
 * Replica el flujo verificado del cliente web (web/js/api.js):
 * login con email/password → `sessionId` como `Bearer` → canales →
 * URL de stream con macros STPLX_* → Widevine.
 *
 * La sesión y las credenciales (si el usuario elige recordar) se guardan
 * cifradas en el dispositivo (EncryptedSharedPreferences); jamás van en
 * el código. Todo exige IP de Venezuela (geoblock del servicio).
 *
 * Llamar fuera del hilo principal. Requiere init(context) una vez.
 */
object ThundernetClient {

    private const val ORIGIN = "https://app.thundernet.net.ve"
    private const val API = "$ORIGIN/wbs/web/api"
    private const val AUTH_URL = "$ORIGIN/wbs/web/auth"

    private const val PREFS = "thundernet_secure"
    private const val KEY_TOKEN = "session_token"
    private const val KEY_ACCOUNT_TOKEN = "account_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"
    private const val KEY_DEVICE_ID = "device_id"

    /** Stream resuelto: URL HLS + DRM Widevine opcional. */
    data class ThunderStream(
        val url: String,
        val drmLicenseUrl: String?,
        val drmHeaders: Map<String, String>
    )

    class AuthException(message: String) : IOException(message)
    class GeoBlockedException : IOException("Servicio no disponible en tu región (geoblock)")

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = EncryptedSharedPreferences.create(
        appContext!!,
        PREFS,
        MasterKey.Builder(appContext!!).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Nombre de la app según el sabor (bplay / TruenoTv). */
    private fun appName(): String =
        if (BuildConfig.FLAVOR == "truenotv") "TruenoTv" else "bplay"

    var sessionToken: String?
        get() = prefs().getString(KEY_TOKEN, null)
        private set(v) = prefs().edit().putString(KEY_TOKEN, v).apply()

    /** Token de cuenta para reproducción: separado del token de sesión. */
    var accountToken: String?
        get() = prefs().getString(KEY_ACCOUNT_TOKEN, null)
        private set(v) = prefs().edit().putString(KEY_ACCOUNT_TOKEN, v).apply()

    /** ID de dispositivo persistente para las macros STPLX_*. */
    fun deviceId(): String {
        val p = prefs()
        return p.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            p.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    fun isLoggedIn(): Boolean = !sessionToken.isNullOrBlank()

    fun logout() {
        prefs().edit().remove(KEY_TOKEN).remove(KEY_ACCOUNT_TOKEN).remove(KEY_REFRESH).apply()
    }

    /**
     * Login. Guarda el token y, si remember=true, las credenciales para
     * re-login silencioso. Lanza AuthException o GeoBlockedException.
     */
    @Throws(IOException::class)
    fun login(email: String, password: String, remember: Boolean = true): String {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("manualProfileManagement", true)
            put("model", "${appName()} Android ${BuildConfig.VERSION_NAME}")
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(AUTH_URL)
            .post(body)
            .header("User-Agent", DailymotionClient.USER_AGENT)
            .header("MW-Paid-Content-Allowed", "true")
            .header("MW-InApp-Subscription-Payment-Supported", "true")
            .build()

        val payload = rawCall(req, auth = false)
        if (payload.optString("status") != "OK") {
            val code = payload.optString("errorCode")
            val msg = payload.optString("message")
            if ("geo" in code.lowercase() || "geo" in msg.lowercase()) throw GeoBlockedException()
            throw AuthException(msg.ifBlank { "Credenciales inválidas ($code)" })
        }
        val data = payload.optJSONObject("payload") ?: throw AuthException("Respuesta inesperada")
        val token = pickToken(data) ?: throw AuthException("Sin token en la respuesta")
        sessionToken = token
        prefs().edit()
            .putString(KEY_REFRESH, data.optString("refreshToken").ifBlank { data.optString("refresh_token") })
            .apply()
        val e = prefs().edit()
        if (remember) {
            e.putString(KEY_EMAIL, email).putString(KEY_PASSWORD, password)
        } else {
            e.remove(KEY_EMAIL).remove(KEY_PASSWORD)
        }
        e.apply()
        // Token de cuenta para reproducción (no fatal si falla)
        runCatching { refreshAccountToken() }
        return token
    }

    @Throws(IOException::class)
    private fun refreshAccountToken() {
        val payload = rawCall(
            Request.Builder().url("$API/v3/account/access_token")
                .header("Authorization", "Bearer $sessionToken").build()
        )
        // El token de cuenta va en su propio campo: jamás debe reemplazar
        // el token de sesión (eso rompía el login en bucle).
        pickToken(payload.optJSONObject("payload"))?.let { accountToken = it }
    }

    /** Re-login silencioso con credenciales guardadas. */
    @Throws(IOException::class)
    private fun silentRelogin(): Boolean {
        val email = prefs().getString(KEY_EMAIL, null)
        val password = prefs().getString(KEY_PASSWORD, null)
        if (email.isNullOrBlank() || password.isNullOrBlank()) return false
        return runCatching { login(email, password, remember = true); true }.getOrDefault(false)
    }

    @Throws(IOException::class)
    private fun tryRefresh(): Boolean {
        val rt = prefs().getString(KEY_REFRESH, null) ?: return false
        val body = JSONObject().put("refreshToken", rt)
            .toString().toRequestBody("application/json".toMediaType())
        return runCatching {
            val payload = rawCall(
                Request.Builder().url("$API/v3/session/refresh").post(body)
                    .header("Authorization", "Bearer $sessionToken").build(),
                auth = false
            )
            val token = pickToken(payload.optJSONObject("payload")) ?: return false
            sessionToken = token
            true
        }.getOrDefault(false)
    }

    private fun pickToken(payload: JSONObject?): String? {
        if (payload == null) return null
        return payload.optString("token").ifBlank { null }
            ?: payload.optString("accessToken").ifBlank { null }
            ?: payload.optString("access_token").ifBlank { null }
            ?: payload.optString("sessionToken").ifBlank { null }
            ?: payload.optString("sessionId").ifBlank { null }
            ?: payload.optString("jwt").ifBlank { null }
    }

    /** Llamada cruda: devuelve el JSON completo (status/payload). */
    @Throws(IOException::class)
    private fun rawCall(req: Request, auth: Boolean = true): JSONObject {
        val builder = req.newBuilder()
        if (auth) {
            val token = sessionToken ?: throw AuthException("Sesión no iniciada")
            builder.header("Authorization", "Bearer $token")
        }
        Net.client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401) {
                // El servidor devuelve 401 CON cuerpo JSON (errorCode/message):
                // no descartarlo, el mensaje real es lo que debe ver el usuario.
                val err = runCatching { JSONObject(body) }.getOrNull()
                val code = err?.optString("errorCode").orEmpty()
                val msg = err?.optString("message").orEmpty()
                if ("geo" in code.lowercase() || "geo" in msg.lowercase()) {
                    throw GeoBlockedException()
                }
                throw AuthException(
                    when {
                        code == "WG0008" -> "Credenciales inválidas"
                        msg.isNotBlank() -> msg
                        code.isNotBlank() -> "Error $code"
                        else -> "UNAUTHORIZED"
                    }
                )
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return JSONObject(body)
        }
    }

    /**
     * GET autenticado con reintento: ante 401 prueba refresh y luego
     * re-login silencioso (igual que el cliente web).
     */
    @Throws(IOException::class)
    private fun apiGet(path: String, query: Map<String, String> = emptyMap()): JSONObject {
        val qs = query.filterValues { it.isNotBlank() }
            .entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val url = "$API$path${if (qs.isNotBlank()) "?$qs" else ""}"
        try {
            return rawCall(Request.Builder().url(url).build())
        } catch (e: AuthException) {
            if (e.message != "UNAUTHORIZED") throw e
            if (tryRefresh()) return rawCall(Request.Builder().url(url).build())
            if (silentRelogin()) return rawCall(Request.Builder().url(url).build())
            throw AuthException("Sesión vencida, inicia sesión de nuevo")
        }
    }

    private fun payloadArray(obj: JSONObject): List<JSONObject> {
        val payload = obj.optJSONObject("payload") ?: return emptyList()
        val arr = payload.optJSONArray("list")
            ?: payload.optJSONArray("items")
            ?: payload.optJSONArray("channels")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** Canales de TV en vivo. */
    @Throws(IOException::class)
    fun getChannels(): List<StreamItem> {
        val res = apiGet("/v3/channels/tv")
        return payloadArray(res).mapNotNull { c ->
            val id = c.optString("id").ifBlank { return@mapNotNull null }
            StreamItem(
                id = "tn:$id",
                title = c.optString("name").ifBlank { id },
                thumbnail = c.optString("logoUrl").takeIf { it.isNotBlank() },
                channel = c.optString("categoryName").takeIf { it.isNotBlank() } ?: "TruenoTv",
                logo = null
            )
        }
    }

    /** URL de stream + DRM de un canal. Aplica las macros STPLX_*. */
    @Throws(IOException::class)
    fun getChannelStream(channelId: String): ThunderStream {
        val id = channelId.removePrefix("tn:")
        val res = apiGet("/v3/channels/tv/${URLEncoder.encode(id, "UTF-8")}/url")
        val payload = res.optJSONObject("payload") ?: throw IOException("Sin stream")
        val info = parseStreamInfo(payload)
        val url = rewriteMacros(info.url ?: throw IOException("Sin URL de stream"))
        return ThunderStream(url, info.drmLicenseUrl, info.drmHeaders)
    }

    private data class StreamInfo(
        val url: String?,
        val drmLicenseUrl: String?,
        val drmHeaders: Map<String, String>
    )

    private fun parseStreamInfo(payload: JSONObject): StreamInfo {
        if (payload.length() == 0) return StreamInfo(null, null, emptyMap())
        val url = payload.optString("url").ifBlank { null }
            ?: payload.optString("streamUrl").ifBlank { null }
            ?: payload.optString("hls").ifBlank { null }
        val drm = payload.optJSONObject("drm")
        var licenseUrl = drm?.optString("licenseUrl")?.ifBlank { null }
            ?: payload.optString("licenseUrl").ifBlank { null }
        @Suppress("UNCHECKED_CAST")
        val headers = (drm?.optJSONObject("headers")?.let { jsonToMap(it) }
            ?: emptyMap()) as Map<String, String>
        return StreamInfo(url, licenseUrl, headers)
    }

    private fun jsonToMap(o: JSONObject): Map<String, String> {
        return o.keys().asSequence().associateWith { o.optString(it) }
    }

    /**
     * Reescribe las macros [STPLX_*] de la URL (igual que el cliente web).
     * Valores para Android/bplay.
     */
    fun rewriteMacros(url: String): String {
        if ("STPLX_" !in url) return url
        val now = Date()
        val dt = SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
        val tm = SimpleDateFormat("HHmmss", Locale.US).format(now)
        val did = deviceId()
        val vals = mapOf(
            "STPLX_DEVICE_ID" to did,
            "STPLX_APP_NAME" to "bplay",
            "STPLX_APP_VERSION" to BuildConfig.VERSION_NAME,
            "STPLX_DEVICE_TYPE" to "ANDROID",
            "STPLX_DEVICE_MODEL" to Build.MODEL,
            "STPLX_DEVICE_MANUFACTURER" to Build.MANUFACTURER,
            "STPLX_OS" to "Android",
            "STPLX_DOMAIN" to "app.thundernet.net.ve",
            "STPLX_DT" to dt,
            "STPLX_TM" to tm,
            "STPLX_ANM" to "",
            "STPLX_AVER" to "",
            "STPLX_UPAL" to "",
            "STPLX_AD_ID" to "",
            "STPLX_SESSION_CONTENT_UUID" to did,
            "STPLX_PLAYER_W" to "0",
            "STPLX_PLAYER_H" to "0"
        )
        return Regex("\\[?STPLX_[A-Z_]+\\]?").replace(url) { m ->
            val key = m.value.replace("[", "").replace("]", "")
            URLEncoder.encode(vals[key] ?: "", "UTF-8")
        }
    }
}
