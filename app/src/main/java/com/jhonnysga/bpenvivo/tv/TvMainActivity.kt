package com.jhonnysga.bpenvivo.tv

import android.os.Bundle
import android.widget.Button
import androidx.fragment.app.FragmentActivity
import com.jhonnysga.bpenvivo.R

/**
 * Actividad principal para Android TV / Fire TV Stick.
 * Aparece en el launcher de TV gracias al intent-filter LEANBACK_LAUNCHER.
 * Incluye botón "Actualizar" (navegable con el D-pad) para recargar
 * la lista de canales sin salir de la app.
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

        val refreshBtn = findViewById<Button>(R.id.tv_refresh)
        refreshBtn.setOnClickListener {
            val fragment = browseFragment() ?: return@setOnClickListener
            refreshBtn.isEnabled = false
            refreshBtn.text = getString(R.string.refreshing)
            fragment.refresh {
                refreshBtn.text = getString(R.string.refresh)
                refreshBtn.isEnabled = true
            }
        }
    }

    private fun browseFragment(): TvBrowseFragment? {
        return supportFragmentManager.findFragmentById(R.id.tv_container) as? TvBrowseFragment
    }
}
