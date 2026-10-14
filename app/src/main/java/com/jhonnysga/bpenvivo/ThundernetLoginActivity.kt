package com.jhonnysga.bpenvivo

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Login de Thundernet TV GO (teléfono y Fire TV: el control abre
 * el teclado en los campos). Las credenciales se guardan cifradas
 * en el dispositivo si el login es exitoso.
 */
class ThundernetLoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_thundernet_login)

        val email = findViewById<EditText>(R.id.login_email)
        val password = findViewById<EditText>(R.id.login_password)
        val button = findViewById<Button>(R.id.login_button)
        val error = findViewById<TextView>(R.id.login_error)
        val progress = findViewById<ProgressBar>(R.id.login_progress)

        email.requestFocus()
        button.setOnClickListener {
            val e = email.text.toString().trim()
            val p = password.text.toString()
            if (e.isBlank() || p.isBlank()) {
                error.text = "Escribe tu usuario y contraseña"
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            error.visibility = View.GONE
            progress.visibility = View.VISIBLE
            button.isEnabled = false
            Thread {
                val result = runCatching { ThundernetClient.login(e, p, remember = true) }
                runOnUiThread {
                    progress.visibility = View.GONE
                    button.isEnabled = true
                    result
                        .onSuccess {
                            setResult(RESULT_OK)
                            finish()
                        }
                        .onFailure { ex ->
                            error.text = when (ex) {
                                is ThundernetClient.GeoBlockedException ->
                                    "Thundernet solo funciona con internet de Venezuela"
                                else -> ex.message ?: "No se pudo iniciar sesión"
                            }
                            error.visibility = View.VISIBLE
                        }
                }
            }.start()
        }
    }
}
