package com.jhonnysga.bpenvivo.mitv

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.jhonnysga.bpenvivo.R
import com.jhonnysga.bpenvivo.mitv.tv.MiTvTvActivity

/**
 * Login de MiTV (Thundernet TV GO / Setplex).
 *
 * El usuario escribe su usuario y clave AQUÍ, en su dispositivo. Viajan solo
 * hacia `app.thundernet.net.ve` durante el login; el JWT resultante se guarda
 * cifrado (ver [SessionStore]). Con "recordarme", también se guardan usuario
 * y clave cifrados para renovar la sesión sin reescribirlos.
 *
 * Funciona con D-pad en Fire TV (los campos son enfocables).
 */
class MiTvLoginActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private lateinit var userField: TextInputEditText
    private lateinit var passField: TextInputEditText
    private lateinit var rememberBox: CheckBox
    private lateinit var loginBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mitv_login)
        store = SessionStore(this)

        // Si ya hay sesión válida, se entra directo.
        if (store.hasSession()) {
            goNext()
            return
        }

        userField = findViewById(R.id.mitv_user)
        passField = findViewById(R.id.mitv_pass)
        rememberBox = findViewById(R.id.mitv_remember)
        loginBtn = findViewById(R.id.mitv_login_btn)
        progress = findViewById(R.id.mitv_progress)
        errorView = findViewById(R.id.mitv_error)

        // Prefill con lo recordado (sin exponer la clave en claro fuera del campo).
        store.getCredentials()?.let { (email, pass) ->
            userField.setText(email)
            passField.setText(pass)
        }
        store.getEmail()?.let { userField.setText(it) }

        userField.requestFocus()
        loginBtn.setOnClickListener { doLogin() }
    }

    private fun doLogin() {
        val email = userField.text?.toString()?.trim().orEmpty()
        val password = passField.text?.toString().orEmpty()
        if (email.isBlank() || password.isBlank()) {
            showError(getString(R.string.mitv_login_empty))
            return
        }
        setBusy(true)
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        Thread {
            val result = runCatching {
                SetplexClient.login(email, password, deviceModel)
            }
            runOnUiThread {
                setBusy(false)
                result
                    .onSuccess { jwt ->
                        store.saveSession(jwt, email)
                        if (rememberBox.isChecked) {
                            store.saveCredentials(email, password)
                        } else {
                            store.clearCredentials()
                        }
                        goNext()
                    }
                    .onFailure { e ->
                        showError(friendlyError(e))
                    }
            }
        }.start()
    }

    private fun friendlyError(e: Throwable): String {
        return when (e) {
            is SetplexClient.GeoblockException ->
                getString(R.string.mitv_geoblock)
            is SetplexClient.AuthException ->
                getString(R.string.mitv_login_auth, e.message ?: "")
            else -> {
                val m = e.message.orEmpty()
                if (m.contains("Bad credentials", ignoreCase = true)) {
                    getString(R.string.mitv_login_bad)
                } else {
                    getString(R.string.mitv_login_error, m)
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        loginBtn.isEnabled = !busy
        userField.isEnabled = !busy
        passField.isEnabled = !busy
        if (busy) errorView.visibility = View.GONE
    }

    private fun showError(msg: String) {
        errorView.text = msg
        errorView.visibility = View.VISIBLE
    }

    private fun goNext() {
        val next = if (isTelevision()) {
            Intent(this, MiTvTvActivity::class.java)
        } else {
            Intent(this, MiTvBrowserActivity::class.java)
        }
        // Limpia la pila para no volver al login con "atrás".
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(next)
        finish()
    }

    private fun isTelevision(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
