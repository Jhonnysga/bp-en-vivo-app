package com.jhonnysga.bpenvivo

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.jhonnysga.bpenvivo.mitv.MiTvBrowserActivity
import com.jhonnysga.bpenvivo.mitv.MiTvLoginActivity
import com.jhonnysga.bpenvivo.mitv.SessionStore

/**
 * Pantalla principal (teléfonos/tablets): lista de transmisiones en vivo
 * con "tirar para actualizar". Tocar una abre el reproductor.
 *
 * El botón "MiTV" abre la sección de Thundernet TV GO (canales, películas,
 * series y catchup con la cuenta del usuario).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button

    private val adapter = StreamAdapter { openPlayer(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sin sesión de MiTV, el login es lo primero que se ve (sin pantallas intermedias).
        if (!SessionStore(this).hasSession()) {
            startActivity(Intent(this, MiTvLoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        swipe = findViewById(R.id.swipe)
        list = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty_view)
        errorView = findViewById(R.id.error_view)
        errorText = findViewById(R.id.error_text)
        retryButton = findViewById(R.id.retry_button)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        swipe.setOnRefreshListener { load() }
        retryButton.setOnClickListener { load() }
        findViewById<Button>(R.id.mitv_button).setOnClickListener { openMiTv() }

        load()
    }

    /** Abre MiTV: al navegador si hay sesión, al login si no. */
    private fun openMiTv() {
        val store = SessionStore(this)
        val target = if (store.hasSession()) {
            MiTvBrowserActivity::class.java
        } else {
            MiTvLoginActivity::class.java
        }
        startActivity(Intent(this, target))
    }

    private fun load() {
        swipe.isRefreshing = true
        errorView.visibility = View.GONE
        Thread {
            val baseUrl = BackendResolver.resolve(BuildConfig.BACKEND_URL)
            val result = runCatching { ApiClient.fetchLive(baseUrl) }
            runOnUiThread {
                swipe.isRefreshing = false
                result
                    .onSuccess { items ->
                        adapter.submit(items)
                        val empty = items.isEmpty()
                        list.visibility = if (empty) View.GONE else View.VISIBLE
                        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
                    }
                    .onFailure { e ->
                        list.visibility = View.GONE
                        emptyView.visibility = View.GONE
                        errorText.text = getString(R.string.error_load, e.message ?: "")
                        errorView.visibility = View.VISIBLE
                    }
            }
        }.start()
    }

    private fun openPlayer(item: StreamItem) {
        // La URL ya quedó resuelta (y cacheada) en load(); se reutiliza.
        val baseUrl = BackendResolver.resolve(BuildConfig.BACKEND_URL)
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_ID, item.id)
                putExtra(EXTRA_STREAM_TITLE, item.title)
                putExtra(EXTRA_BACKEND_URL, baseUrl)
            }
        )
    }

    companion object {
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_STREAM_TITLE = "stream_title"
        const val EXTRA_BACKEND_URL = "backend_url"
    }
}
