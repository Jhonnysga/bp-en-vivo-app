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
import com.jhonnysga.bpenvivo.BackendResolver
import com.jhonnysga.bpenvivo.BuildConfig
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_BACKEND_URL
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_STREAM_ID
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_STREAM_TITLE
import com.jhonnysga.bpenvivo.PlayerActivity
import com.jhonnysga.bpenvivo.R
import com.jhonnysga.bpenvivo.StreamItem
import com.jhonnysga.bpenvivo.mitv.tv.MiTvTvActivity

/**
 * Navegador Leanback: una fila "En vivo" con tarjetas por transmisión.
 * Todo se maneja con el control remoto (D-pad).
 */
class TvBrowseFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val cardPresenter = TvCardPresenter()
    @Volatile private var baseUrl: String = BuildConfig.BACKEND_URL

    companion object {
        /** Id reservado de la tarjeta que abre MiTV (no es un stream real). */
        private const val MITV_OPEN_ID = "mitv:open"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = true
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is StreamItem) {
                if (item.id == MITV_OPEN_ID) {
                    startActivity(Intent(requireContext(), MiTvTvActivity::class.java))
                    return@OnItemViewClickedListener
                }
                startActivity(
                    Intent(requireContext(), PlayerActivity::class.java).apply {
                        putExtra(EXTRA_STREAM_ID, item.id)
                        putExtra(EXTRA_STREAM_TITLE, item.title)
                        putExtra(EXTRA_BACKEND_URL, baseUrl)
                    }
                )
            }
        }

        load()
    }

    private fun load() {
        Thread {
            baseUrl = BackendResolver.resolve(BuildConfig.BACKEND_URL)
            val items = runCatching { ApiClient.fetchLive(baseUrl) }
                .getOrDefault(emptyList())
            activity?.runOnUiThread {
                rowsAdapter.clear()
                // Fila MiTV primero: abre la sección de Thundernet TV GO.
                val mitvAdapter = ArrayObjectAdapter(cardPresenter)
                mitvAdapter.add(
                    StreamItem(
                        id = MITV_OPEN_ID,
                        title = getString(R.string.mitv_open),
                        thumbnail = null,
                        channel = getString(R.string.mitv_open_desc),
                        logo = null
                    )
                )
                rowsAdapter.add(ListRow(HeaderItem(getString(R.string.mitv_title)), mitvAdapter))
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
