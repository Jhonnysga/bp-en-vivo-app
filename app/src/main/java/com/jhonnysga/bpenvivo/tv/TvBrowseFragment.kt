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
import com.jhonnysga.bpenvivo.BuildConfig
import com.jhonnysga.bpenvivo.DailymotionClient
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_CHANNEL_IDS
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_CHANNEL_INDEX
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_CHANNEL_TITLES
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_SOURCE
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_STREAM_ID
import com.jhonnysga.bpenvivo.MainActivity.Companion.EXTRA_STREAM_TITLE
import com.jhonnysga.bpenvivo.MainActivity.Source
import com.jhonnysga.bpenvivo.PlayerActivity
import com.jhonnysga.bpenvivo.R
import com.jhonnysga.bpenvivo.StreamItem
import com.jhonnysga.bpenvivo.ThundernetClient
import com.jhonnysga.bpenvivo.ThundernetLoginActivity

/**
 * Navegador Leanback: una sola fila según el sabor:
 * bplay → "BéisbolPlay" (Dailymotion); truenotv → "TruenoTv".
 * Todo se maneja con el control remoto (D-pad).
 */
class TvBrowseFragment : BrowseSupportFragment() {

    /** Fila única de este sabor. */
    private val isTrueno: Boolean
        get() = BuildConfig.FLAVOR == "truenotv"

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val cardPresenter = TvCardPresenter()

    private var beisbolItems: List<StreamItem> = emptyList()
    private var thunderItems: List<StreamItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = if (isTrueno) "TruenoTv" else getString(R.string.app_name)
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = true
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is StreamItem) {
                if (item.id == LOGIN_CARD_ID) {
                    startActivity(Intent(requireContext(), ThundernetLoginActivity::class.java))
                    return@OnItemViewClickedListener
                }
                openPlayer(item)
            }
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        // Al volver del login, recargar por si hay sesión nueva.
        load()
    }

    private fun openPlayer(item: StreamItem) {
        val items = if (isTrueno) thunderItems else beisbolItems
        val ids = ArrayList(items.map { it.id })
        val titles = ArrayList(items.map { it.title })
        val index = items.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        startActivity(
            Intent(requireContext(), PlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_ID, item.id)
                putExtra(EXTRA_STREAM_TITLE, item.title)
                putExtra(
                    EXTRA_SOURCE,
                    if (isTrueno) Source.THUNDERNET.name else Source.BEISBOLPLAY.name
                )
                putStringArrayListExtra(EXTRA_CHANNEL_IDS, ids)
                putStringArrayListExtra(EXTRA_CHANNEL_TITLES, titles)
                putExtra(EXTRA_CHANNEL_INDEX, index)
            }
        )
    }

    private fun load() {
        refresh()
    }

    /**
     * Recarga la fila única del sabor. onDone se ejecuta en el hilo UI al terminar.
     */
    fun refresh(onDone: () -> Unit = {}) {
        Thread {
            beisbolItems = if (!isTrueno) {
                runCatching { DailymotionClient.fetchLive() }.getOrDefault(emptyList())
            } else emptyList()
            thunderItems = if (isTrueno && ThundernetClient.isLoggedIn()) {
                runCatching { ThundernetClient.getChannels() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            activity?.runOnUiThread {
                rowsAdapter.clear()
                if (!isTrueno) {
                    // Fila única: BéisbolPlay
                    val bpRow = ArrayObjectAdapter(cardPresenter)
                    beisbolItems.forEach(bpRow::add)
                    rowsAdapter.add(ListRow(HeaderItem("BéisbolPlay en vivo"), bpRow))
                } else {
                    // Fila única: TruenoTv (o tarjeta de login)
                    val tnRow = ArrayObjectAdapter(cardPresenter)
                    if (ThundernetClient.isLoggedIn()) {
                        thunderItems.forEach(tnRow::add)
                    } else {
                        tnRow.add(
                            StreamItem(
                                id = LOGIN_CARD_ID,
                                title = "Iniciar sesión en TruenoTv",
                                thumbnail = null,
                                channel = "TV GO",
                                logo = null
                            )
                        )
                    }
                    rowsAdapter.add(ListRow(HeaderItem("TruenoTv"), tnRow))
                }
                if (beisbolItems.isEmpty() && thunderItems.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.empty_live, Toast.LENGTH_LONG).show()
                }
                onDone()
            }
        }.start()
    }

    companion object {
        const val LOGIN_CARD_ID = "tn:login"
    }
}
