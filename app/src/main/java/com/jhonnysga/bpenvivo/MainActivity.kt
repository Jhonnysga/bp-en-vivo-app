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

/**
 * Pantalla principal (teléfonos/tablets): lista de transmisiones en vivo
 * con "tirar para actualizar". Tocar una abre el reproductor.
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

        load()
    }

    private fun load() {
        swipe.isRefreshing = true
        errorView.visibility = View.GONE
        Thread {
            val result = runCatching { ApiClient.fetchLive(BuildConfig.BACKEND_URL) }
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
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_ID, item.id)
                putExtra(EXTRA_STREAM_TITLE, item.title)
            }
        )
    }

    companion object {
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_STREAM_TITLE = "stream_title"
    }
}
