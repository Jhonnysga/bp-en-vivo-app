package com.jhonnysga.bpenvivo.tv

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import com.jhonnysga.bpenvivo.DailymotionClient
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_CHANNEL_IDS
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_CHANNEL_INDEX
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_CHANNEL_TITLES
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_STREAM_ID
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_STREAM_TITLE
import com.jhonnysga.bpenvivo.PlayerActivity
import com.jhonnysga.bpenvivo.R
import com.jhonnysga.bpenvivo.StreamItem

/**
 * Navegador Leanback: una fila "En vivo" con tarjetas por transmisión.
 * Todo se maneja con el control remoto (D-pad).
 */
class TvBrowseFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val cardPresenter = TvCardPresenter()
    @Volatile private var lastItems: List<StreamItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = true
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is StreamItem) {
                val ids = ArrayList(lastItems.map { it.id })
                val titles = ArrayList(lastItems.map { it.title })
                val index = lastItems.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                startActivity(
                    Intent(requireContext(), PlayerActivity::class.java).apply {
                        putExtra(EXTRA_STREAM_ID, item.id)
                        putExtra(EXTRA_STREAM_TITLE, item.title)
                        putStringArrayListExtra(EXTRA_CHANNEL_IDS, ids)
                        putStringArrayListExtra(EXTRA_CHANNEL_TITLES, titles)
                        putExtra(EXTRA_CHANNEL_INDEX, index)
                    }
                )
            }
        }

        load()
    }

    private fun load() {
        refresh()
    }

    /**
     * Recarga la lista de canales desde Dailymotion.
     * onDone se ejecuta en el hilo UI al terminar.
     */
    fun refresh(onDone: () -> Unit = {}) {
        Thread {
            // Sin PC: la lista sale directo de Dailymotion.
            val items = runCatching { DailymotionClient.fetchLive() }
                .getOrDefault(emptyList())
            lastItems = items
            activity?.runOnUiThread {
                rowsAdapter.clear()
                val rowAdapter = ArrayObjectAdapter(cardPresenter)
                items.forEach(rowAdapter::add)
                rowsAdapter.add(ListRow(HeaderItem(getString(R.string.tv_row_live)), rowAdapter))
                if (items.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.empty_live,
                        Toast.LENGTH_LONG
                    ).show()
                }
                onDone()
            }
        }.start()
    }
}
