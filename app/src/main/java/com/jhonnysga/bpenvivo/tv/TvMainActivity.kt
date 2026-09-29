package com.jhonnysga.bpenvivo.tv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.jhonnysga.bpenvivo.R

/**
 * Actividad principal para Android TV / Fire TV Stick.
 * Aparece en el launcher de TV gracias al intent-filter LEANBACK_LAUNCHER.
 */
class TvMainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tv_container, TvBrowseFragment())
                .commit()
        }
    }
}
