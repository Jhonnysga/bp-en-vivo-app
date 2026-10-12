package com.jhonnysga.bpenvivo.mitv

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.jhonnysga.bpenvivo.ImageLoader
import com.jhonnysga.bpenvivo.MainActivity
import com.jhonnysga.bpenvivo.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Navegador de MiTV: canales en vivo (con EPG ahora/siguiente), películas,
 * series, temporadas/episodios y catchup por canal.
 *
 * El modo llega por [EXTRA_MODE]; los modos de detalle (temporadas,
 * episodios, catchup) reciben además los ids necesarios.
 */
class MiTvBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CHANNELS = "channels"
        const val MODE_MOVIES = "movies"
        const val MODE_SERIES = "series"
        const val MODE_SEASONS = "seasons"
        const val MODE_EPISODES = "episodes"
        const val MODE_CATCHUP = "catchup"

        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SEASON_ID = "season_id"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_TITLE = "title"
    }

    private data class PosterItem(
        val title: String,
        var subtitle: String?,
        val imageUrl: String?,
        val onClick: () -> Unit,
        val onLongClick: (() -> Boolean)? = null
    )

    private lateinit var store: SessionStore
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var grid: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private lateinit var sectionSpinner: Spinner
    private lateinit var toolbar: Toolbar

    private var mode: String = MODE_CHANNELS
    private var seriesId: String = ""
    private var seasonId: String = ""
    private var channelId: String = ""
    private var categories: List<TvCategory> = emptyList()
    private var channels: List<TvChannel> = emptyList()
    private var epg: Map<String, List<EpgProgram>> = emptyMap()
    private var selectedCategory = 0 // 0 = todas

    private val items = ArrayList<PosterItem>()
    private val adapter = PosterAdapter(items)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mitv_browser)
        store = SessionStore(this)

        if (!store.hasSession()) {
            startActivity(Intent(this, MiTvLoginActivity::class.java))
            finish()
            return
        }

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CHANNELS
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        seasonId = intent.getStringExtra(EXTRA_SEASON_ID).orEmpty()
        channelId = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE)

        toolbar = findViewById(R.id.mitv_toolbar)
        toolbar.title = title ?: getString(R.string.mitv_title)
        setSupportActionBar(toolbar)

        swipe = findViewById(R.id.mitv_swipe)
        grid = findViewById(R.id.mitv_grid)
        emptyView = findViewById(R.id.mitv_empty)
        errorView = findViewById(R.id.mitv_error_view)
        errorText = findViewById(R.id.mitv_error_text)
        retryButton = findViewById(R.id.mitv_retry)
        sectionSpinner = findViewById(R.id.mitv_section)

        val span = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 5 else 3
        grid.layoutManager = GridLayoutManager(this, span)
        grid.adapter = adapter
        swipe.setOnRefreshListener { load() }
        retryButton.setOnClickListener { load() }

        if (isTopLevel()) {
            setupSectionSpinner()
        } else {
            sectionSpinner.visibility = View.GONE
        }

        load()
    }

    private fun isTopLevel(): Boolean =
        mode == MODE_CHANNELS || mode == MODE_MOVIES || mode == MODE_SERIES

    private fun setupSectionSpinner() {
        // El spinner filtra por categoría (solo en modo canales); la sección
        // (Canales/Películas/Series) se cambia desde el menú.
        sectionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (mode == MODE_CHANNELS && pos != selectedCategory) {
                    selectedCategory = pos
                    refreshChannelItems()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun refreshCategorySpinner() {
        val labels = ArrayList<String>()
        labels.add(getString(R.string.mitv_all_categories))
        labels.addAll(categories.map { it.name })
        sectionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        sectionSpinner.setSelection(selectedCategory.coerceAtMost(labels.size - 1), false)
        sectionSpinner.visibility = if (mode == MODE_CHANNELS && categories.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 10, 0, getString(R.string.mitv_channels))
        menu.add(0, 11, 0, getString(R.string.mitv_movies))
        menu.add(0, 12, 0, getString(R.string.mitv_series))
        menu.add(0, 13, 0, getString(R.string.app_name))
        menu.add(0, 1, 0, getString(R.string.mitv_logout))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                store.logout()
                startActivity(Intent(this, MiTvLoginActivity::class.java))
                finish()
                return true
            }
            13 -> {
                // Volver a la sección de BéisbolPlay (no requiere sesión MiTV).
                startActivity(Intent(this, MainActivity::class.java))
                return true
            }
            10, 11, 12 -> {
                val newMode = when (item.itemId) {
                    11 -> MODE_MOVIES
                    12 -> MODE_SERIES
                    else -> MODE_CHANNELS
                }
                if (newMode != mode && isTopLevel()) {
                    mode = newMode
                    selectedCategory = 0
                    toolbar.title = getString(R.string.mitv_title)
                    load()
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun load() {
        swipe.isRefreshing = true
        errorView.visibility = View.GONE
        Thread {
            val result = runCatching {
                when (mode) {
                    MODE_CHANNELS -> loadChannels()
                    MODE_MOVIES -> loadMovies()
                    MODE_SERIES -> loadSeries()
                    MODE_SEASONS -> loadSeasons()
                    MODE_EPISODES -> loadEpisodes()
                    MODE_CATCHUP -> loadCatchup()
                    else -> loadChannels()
                }
            }
            runOnUiThread {
                swipe.isRefreshing = false
                result
                    .onSuccess { list ->
                        items.clear()
                        items.addAll(list)
                        adapter.notifyDataSetChanged()
                        val empty = list.isEmpty()
                        grid.visibility = if (empty) View.GONE else View.VISIBLE
                        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
                    }
                    .onFailure { e ->
                        if (e is SetplexClient.AuthException) {
                            // Sesión muerta: al login.
                            startActivity(Intent(this, MiTvLoginActivity::class.java))
                            finish()
                            return@onFailure
                        }
                        grid.visibility = View.GONE
                        emptyView.visibility = View.GONE
                        errorText.text = e.message ?: getString(R.string.mitv_load_error)
                        errorView.visibility = View.VISIBLE
                    }
            }
        }.start()
    }

    // ------------------------------------------------------------ cargadores

    private fun loadChannels(): List<PosterItem> {
        channels = SetplexClient.getChannels(store)
        if (categories.isEmpty()) {
            categories = runCatching { SetplexClient.getCategories(store) }.getOrDefault(emptyList())
        }
        // EPG del día para "ahora/siguiente" (no fatal si falla).
        epg = runCatching {
            val ids = channels.mapNotNull { it.epgId }.distinct()
            if (ids.isEmpty()) emptyMap() else {
                val now = System.currentTimeMillis()
                val start = startOfDay(now)
                SetplexClient.getEpg(store, ids, start, start + 24 * 3600 * 1000L)
            }
        }.getOrDefault(emptyMap())

        runOnUiThread { refreshCategorySpinner() }
        return buildChannelItems()
    }

    /** Re-filtra por categoría sin ir a la red (la lista ya está en memoria). */
    private fun refreshChannelItems() {
        val list = buildChannelItems()
        items.clear()
        items.addAll(list)
        adapter.notifyDataSetChanged()
        val empty = list.isEmpty()
        grid.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun buildChannelItems(): List<PosterItem> {
        val filtered = if (selectedCategory == 0 || categories.isEmpty()) {
            channels
        } else {
            val catId = categories.getOrNull(selectedCategory - 1)?.id
            channels.filter { it.categoryId == catId }
        }
        return filtered.map { ch ->
            val (now, next) = nowNext(ch.epgId)
            val sub = buildString {
                ch.channelNumber?.let { append(getString(R.string.mitv_ch_number, it)).append("  ") }
                if (now != null) {
                    append("▶ ").append(now.title)
                    if (next != null) append("  •  ").append(next.title)
                } else {
                    ch.categoryName?.let { append(it) }
                }
            }.ifBlank { null }
            PosterItem(
                title = ch.name,
                subtitle = sub,
                imageUrl = ch.logoUrl,
                onClick = {
                    openPlayer(MiTvPlayerActivity.KIND_TV, ch.id, ch.name)
                },
                onLongClick = {
                    // Pulsación larga: programas recientes del canal (catchup 48h).
                    startActivity(
                        Intent(this, MiTvBrowserActivity::class.java).apply {
                            putExtra(EXTRA_MODE, MODE_CATCHUP)
                            putExtra(EXTRA_CHANNEL_ID, ch.id)
                            putExtra(EXTRA_TITLE, getString(R.string.mitv_catchup_title, ch.name))
                        }
                    )
                    true
                }
            )
        }
    }

    private fun loadMovies(): List<PosterItem> =
        SetplexClient.getMovies(store).map { v ->
            PosterItem(v.title, null, v.posterUrl, onClick = {
                openPlayer(MiTvPlayerActivity.KIND_VOD, v.id, v.title)
            }
        }

    private fun loadSeries(): List<PosterItem> =
        SetplexClient.getSeries(store).map { s ->
            PosterItem(s.title, null, s.posterUrl, onClick = {
                startActivity(
                    Intent(this, MiTvBrowserActivity::class.java).apply {
                        putExtra(EXTRA_MODE, MODE_SEASONS)
                        putExtra(EXTRA_SERIES_ID, s.id)
                        putExtra(EXTRA_TITLE, s.title)
                    }
                )
            }
        }

    private fun loadSeasons(): List<PosterItem> =
        SetplexClient.getSeasons(store, seriesId).map { s ->
            PosterItem(s.title, null, s.posterUrl, onClick = {
                startActivity(
                    Intent(this, MiTvBrowserActivity::class.java).apply {
                        putExtra(EXTRA_MODE, MODE_EPISODES)
                        putExtra(EXTRA_SERIES_ID, s.seriesId)
                        putExtra(EXTRA_SEASON_ID, s.id)
                        putExtra(EXTRA_TITLE, s.title)
                    }
                )
            }
        }

    private fun loadEpisodes(): List<PosterItem> =
        SetplexClient.getEpisodes(store, seriesId, seasonId).map { e ->
            PosterItem(e.title, null, e.posterUrl, onClick = {
                openPlayer(
                    MiTvPlayerActivity.KIND_EPISODE, e.id, e.title,
                    e.seriesId, e.seasonId
                )
            }
        }

    private fun loadCatchup(): List<PosterItem> {
        val progs = SetplexClient.getCatchupProgrammes(store, channelId)
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        return progs.map { p ->
            val sub = if (p.startMs > 0) fmt.format(Date(p.startMs)) else null
            PosterItem(p.title, sub, null, onClick = {
                openPlayer(MiTvPlayerActivity.KIND_CATCHUP, p.id, p.title, channelId, p.catchupId)
            })
        }
    }

    private fun openPlayer(
        kind: String,
        id: String,
        title: String,
        extra1: String = "",
        extra2: String = ""
    ) {
        startActivity(
            Intent(this, MiTvPlayerActivity::class.java).apply {
                putExtra(MiTvPlayerActivity.EXTRA_KIND, kind)
                putExtra(MiTvPlayerActivity.EXTRA_ID, id)
                putExtra(MiTvPlayerActivity.EXTRA_TITLE, title)
                putExtra(MiTvPlayerActivity.EXTRA_REF1, extra1)
                putExtra(MiTvPlayerActivity.EXTRA_REF2, extra2)
            }
        )
    }

    private fun nowNext(epgId: String?): Pair<EpgProgram?, EpgProgram?> {
        if (epgId.isNullOrBlank()) return null to null
        val list = epg[epgId] ?: return null to null
        val now = System.currentTimeMillis()
        val current = list.firstOrNull { it.startMs <= now && now < it.endMs }
        val next = list.filter { it.startMs >= now }.minByOrNull { it.startMs }
            ?.takeIf { it != current }
            ?: list.filter { it.startMs > (current?.endMs ?: now) }.minByOrNull { it.startMs }
        return current to next
    }

    private fun startOfDay(ms: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // --------------------------------------------------------------- adapter

    private inner class PosterAdapter(private val data: List<PosterItem>) :
        RecyclerView.Adapter<PosterAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.poster)
            val title: TextView = v.findViewById(R.id.poster_title)
            val sub: TextView = v.findViewById(R.id.poster_sub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_mitv_poster, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = data[position]
            h.title.text = item.title
            if (item.subtitle.isNullOrBlank()) {
                h.sub.visibility = View.GONE
            } else {
                h.sub.visibility = View.VISIBLE
                h.sub.text = item.subtitle
            }
            ImageLoader.load(item.imageUrl, h.poster)
            h.itemView.setOnClickListener { item.onClick() }
            h.itemView.setOnLongClickListener { item.onLongClick?.invoke() ?: false }
        }
    }
}
