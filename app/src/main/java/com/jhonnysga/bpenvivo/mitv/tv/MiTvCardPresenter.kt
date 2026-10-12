package com.jhonnysga.bpenvivo.mitv.tv

import android.content.Intent
import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.jhonnysga.bpenvivo.ImageLoader
import com.jhonnysga.bpenvivo.R
import com.jhonnysga.bpenvivo.mitv.TvChannel

/**
 * Tarjeta de canal para MiTV en TV: logo 16:9, nombre y número/categoría.
 */
class MiTvCardPresenter : Presenter() {

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
        val channel = item as TvChannel
        card.titleText = channel.name
        val sub = buildString {
            channel.channelNumber?.let { append(card.context.getString(R.string.mitv_ch_number, it)) }
            if (channel.categoryName != null) {
                if (isNotEmpty()) append(" • ")
                append(channel.categoryName)
            }
        }
        card.contentText = sub.ifBlank { null }
        card.mainImageView?.let { ImageLoader.load(channel.logoUrl, it) }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as ImageCardView).mainImage = null
    }

    companion object {
        private const val CARD_WIDTH = 320
        private const val CARD_HEIGHT = 180
    }
}
