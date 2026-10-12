package com.jhonnysga.bpenvivo.tv

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.jhonnysga.bpenvivo.R
import com.jhonnysga.bpenvivo.mitv.MiTvLoginActivity
import com.jhonnysga.bpenvivo.mitv.SessionStore

/**
 * Actividad principal para Android TV / Fire TV Stick.
 * Aparece en el launcher de TV gracias al intent-filter LEANBACK_LAUNCHER.
 *
 * Sin sesión de MiTV, el login es lo primero que se ve (sin pantallas intermedias).
 */
class TvMainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionStore(this).hasSession()) {
            startActivity(Intent(this, MiTvLoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_tv_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tv_container, TvBrowseFragment())
                .commit()
        }
    }
}
