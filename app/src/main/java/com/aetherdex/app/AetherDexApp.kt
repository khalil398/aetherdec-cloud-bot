package com.aetherdex.app

import android.app.Application

/**
 * Process-wide application entry point.
 *
 * The only thing we do at startup is initialise the WalletConnect v2 SDK
 * (Core + Sign clients). The Core client owns the WebSocket relay; the
 * Sign client owns the session proposal / approval flow used by the
 * connect button. Both must be initialised exactly once per process.
 *
 * Registered in AndroidManifest.xml via `android:name=".AetherDexApp"`.
 */
class AetherDexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WalletConnectionManager.initialize(this)
    }
}
