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
import com.jhonnysga.bpenvivo.ApiClient
import com.jhonnysga.bpenvivo.BuildConfig
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = true
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is StreamItem) {
                startActivity(
                    Intent(requireContext(), PlayerActivity::class.java).apply {
                        putExtra(EXTRA_STREAM_ID, item.id)
                        putExtra(EXTRA_STREAM_TITLE, item.title)
                    }
                )
            }
        }

        load()
    }

    private fun load() {
        Thread {
            val items = runCatching { ApiClient.fetchLive(BuildConfig.BACKEND_URL) }
                .getOrDefault(emptyList())
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
            }
        }.start()
    }
}
