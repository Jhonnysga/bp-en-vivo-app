package com.jhonnysga.bpenvivo.mitv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Sesión de MiTV: el JWT y (opcional, con "recordarme") las credenciales se
 * guardan cifradas en el dispositivo y NUNCA salen de él salvo hacia
 * `app.thundernet.net.ve` durante el login.
 *
 * Si el keystore falla en algún dispositivo, se usa un respaldo sin cifrar
 * (sigue sin salir del dispositivo).
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse { e ->
        Log.w(TAG, "Keystore no disponible, usando respaldo sin cifrar", e)
        context.getSharedPreferences(PREFS_FALLBACK, Context.MODE_PRIVATE)
    }

    fun saveSession(jwt: String, email: String) {
        prefs.edit().putString(KEY_JWT, jwt).putString(KEY_EMAIL, email).apply()
    }

    fun saveJwt(jwt: String) {
        prefs.edit().putString(KEY_JWT, jwt).apply()
    }

    fun getJwt(): String? = prefs.getString(KEY_JWT, null)

    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)

    fun clearJwt() {
        prefs.edit().remove(KEY_JWT).apply()
    }

    fun hasSession(): Boolean = !getJwt().isNullOrBlank()

    /** "Recordarme": guarda usuario+clave cifrados (solo se envían al login). */
    fun saveCredentials(email: String, password: String) {
        prefs.edit().putString(KEY_REM_EMAIL, email).putString(KEY_REM_PASS, password).apply()
    }

    fun getCredentials(): Pair<String, String>? {
        val e = prefs.getString(KEY_REM_EMAIL, null)
        val p = prefs.getString(KEY_REM_PASS, null)
        return if (!e.isNullOrBlank() && p != null) e to p else null
    }

    fun clearCredentials() {
        prefs.edit().remove(KEY_REM_EMAIL).remove(KEY_REM_PASS).apply()
    }

    fun logout() {
        prefs.edit()
            .remove(KEY_JWT)
            .remove(KEY_EMAIL)
            .remove(KEY_REM_EMAIL)
            .remove(KEY_REM_PASS)
            .apply()
    }

    /** Identificador estable del dispositivo para las macros STPLX_*. */
    fun deviceUuid(): String {
        var id = prefs.getString(KEY_UUID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_UUID, id).apply()
        }
        return id!!
    }

    companion object {
        private const val TAG = "MiTV"
        private const val PREFS = "mitv_session"
        private const val PREFS_FALLBACK = "mitv_session_fb"
        private const val KEY_JWT = "jwt"
        private const val KEY_EMAIL = "email"
        private const val KEY_REM_EMAIL = "rem_email"
        private const val KEY_REM_PASS = "rem_pass"
        private const val KEY_UUID = "device_uuid"
    }
}
