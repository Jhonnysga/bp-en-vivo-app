package com.jhonnysga.bpenvivo.tv

import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.jhonnysga.bpenvivo.ImageLoader
import com.jhonnysga.bpenvivo.R
import com.jhonnysga.bpenvivo.StreamItem

/**
 * Tarjeta de transmisión para la fila "En vivo": miniatura 16:9,
 * título y canal (o "EN VIVO" si no hay canal).
 */
class TvCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val card = viewHolder.view as ImageCardView
        val stream = item as StreamItem
        card.titleText = stream.title
        card.contentText = stream.channel?.takeIf { it.isNotBlank() }
            ?: card.context.getString(R.string.live_badge)
        card.mainImageView?.let { ImageLoader.load(stream.thumbnail, it) }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as ImageCardView).mainImage = null
    }

    companion object {
        private const val CARD_WIDTH = 320
        private const val CARD_HEIGHT = 180
    }
}
