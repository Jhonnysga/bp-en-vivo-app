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
    private var lastItems: List<StreamItem> = emptyList()

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
            // Sin PC: la lista sale directo de Dailymotion.
            val result = runCatching { DailymotionClient.fetchLive() }
            runOnUiThread {
                swipe.isRefreshing = false
                result
                    .onSuccess { items ->
                        lastItems = items
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
        val ids = ArrayList(lastItems.map { it.id })
        val titles = ArrayList(lastItems.map { it.title })
        val index = lastItems.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_ID, item.id)
                putExtra(EXTRA_STREAM_TITLE, item.title)
                putStringArrayListExtra(EXTRA_CHANNEL_IDS, ids)
                putStringArrayListExtra(EXTRA_CHANNEL_TITLES, titles)
                putExtra(EXTRA_CHANNEL_INDEX, index)
            }
        )
    }

    companion object {
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_STREAM_TITLE = "stream_title"
        const val EXTRA_CHANNEL_IDS = "channel_ids"
        const val EXTRA_CHANNEL_TITLES = "channel_titles"
        const val EXTRA_CHANNEL_INDEX = "channel_index"
    }
}
