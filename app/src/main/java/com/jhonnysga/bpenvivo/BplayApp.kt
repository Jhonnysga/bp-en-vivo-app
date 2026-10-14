package com.jhonnysga.bpenvivo

import android.app.Application

/** Inicializa los clientes que necesitan contexto. */
class BplayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ChannelBackup.init(this)
        ThundernetClient.init(this)
    }
}
