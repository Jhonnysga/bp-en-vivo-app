package com.jhonnysga.bpenvivo.mitv.tv

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import com.jhonnysga.bpenvivo.R
import com.jhonnysga.bpenvivo.mitv.MiTvLoginActivity
import com.jhonnysga.bpenvivo.mitv.MiTvPlayerActivity
import com.jhonnysga.bpenvivo.mitv.SessionStore
import com.jhonnysga.bpenvivo.mitv.SetplexClient
import com.jhonnysga.bpenvivo.mitv.TvChannel
import com.jhonnysga.bpenvivo.tv.TvMainActivity

/**
 * Navegador Leanback de MiTV: una fila por categoría con tarjetas de canales.
 * Todo se maneja con el control remoto (D-pad).
 */
class MiTvTvBrowseFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val cardPresenter = MiTvCardPresenter()
    private lateinit var store: SessionStore

    companion object {
        /** Id reservado de la tarjeta que abre BéisbolPlay (no es un canal). */
        private const val BEISBOL_ID = "mitv:beisbol"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(requireContext())
        title = getString(R.string.mitv_title)
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = true
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is TvChannel) {
                if (item.id == BEISBOL_ID) {
                    startActivity(Intent(requireContext(), TvMainActivity::class.java))
                    return@OnItemViewClickedListener
                }
                startActivity(
                    Intent(requireContext(), MiTvPlayerActivity::class.java).apply {
                        putExtra(MiTvPlayerActivity.EXTRA_KIND, MiTvPlayerActivity.KIND_TV)
                        putExtra(MiTvPlayerActivity.EXTRA_ID, item.id)
                        putExtra(MiTvPlayerActivity.EXTRA_TITLE, item.name)
                    }
                )
            }
        }

        load()
    }

    private fun load() {
        Thread {
            val result = runCatching {
                val channels = SetplexClient.getChannels(store)
                val categories = runCatching { SetplexClient.getCategories(store) }
                    .getOrDefault(emptyList())
                channels to categories
            }
            activity?.runOnUiThread {
                result
                    .onSuccess { (channels, categories) ->
                        rowsAdapter.clear()
                        // Fila BéisbolPlay primero (no requiere sesión MiTV).
                        val beisbolAdapter = ArrayObjectAdapter(cardPresenter)
                        beisbolAdapter.add(
                            TvChannel(
                                id = BEISBOL_ID,
                                name = getString(R.string.app_name),
                                logoUrl = null,
                                channelNumber = null,
                                categoryId = null,
                                categoryName = getString(R.string.mitv_beisbol_desc),
                                epgId = null
                            )
                        )
                        rowsAdapter.add(
                            ListRow(HeaderItem(getString(R.string.app_name)), beisbolAdapter)
                        )
                        if (categories.isEmpty()) {
                            addRow(getString(R.string.mitv_channels), channels)
                        } else {
                            val byCat = channels.groupBy { it.categoryId }
                            // Primero las categorías con canales, en el orden del servidor.
                            for (cat in categories) {
                                val list = byCat[cat.id].orEmpty()
                                if (list.isNotEmpty()) addRow(cat.name, list)
                            }
                            // Canales sin categoría conocida, al final.
                            val rest = channels.filter { ch ->
                                categories.none { it.id == ch.categoryId }
                            }
                            if (rest.isNotEmpty()) {
                                addRow(getString(R.string.mitv_all_categories), rest)
                            }
                        }
                        if (channels.isEmpty()) {
                            Toast.makeText(
                                requireContext(),
                                R.string.mitv_empty,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .onFailure { e ->
                        if (e is SetplexClient.AuthException) {
                            startActivity(Intent(requireContext(), MiTvLoginActivity::class.java))
                        } else {
                            Toast.makeText(
                                requireContext(),
                                e.message ?: getString(R.string.mitv_load_error),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
            }
        }.start()
    }

    private fun addRow(header: String, channels: List<TvChannel>) {
        val rowAdapter = ArrayObjectAdapter(cardPresenter)
        channels.forEach(rowAdapter::add)
        rowsAdapter.add(ListRow(HeaderItem(header), rowAdapter))
    }
}
