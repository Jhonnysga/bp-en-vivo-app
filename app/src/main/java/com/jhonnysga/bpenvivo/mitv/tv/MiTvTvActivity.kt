package com.jhonnysga.bpenvivo.mitv.tv

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.jhonnysga.bpenvivo.R
import com.jhonnysga.bpenvivo.mitv.MiTvLoginActivity
import com.jhonnysga.bpenvivo.mitv.SessionStore

/**
 * Entrada de MiTV en Android TV / Fire TV Stick.
 * Si no hay sesión, manda al login (funciona con D-pad).
 */
class MiTvTvActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_main)
        if (savedInstanceState != null) return
        if (!SessionStore(this).hasSession()) {
            startActivity(Intent(this, MiTvLoginActivity::class.java))
            finish()
            return
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.tv_container, MiTvTvBrowseFragment())
            .commit()
    }
}
