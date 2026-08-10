package com.glaxysu.root

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

class WirelessAdbPairingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pairingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(
            NOTIFICATION_ID,
            WirelessAdbNotifications.buildPairingProgressNotification(this),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pairingJob?.isActive == true) return START_NOT_STICKY
        val pairingCode = intent
            ?.getStringExtra(WirelessAdbNotifications.EXTRA_PAIRING_CODE)
            .orEmpty()
        pairingJob = serviceScope.launch {
            try {
                val connected = withTimeout(PAIRING_TIMEOUT_MILLIS) {
                    runInterruptible {
                        WirelessAdbManager.pair(this@WirelessAdbPairingService, pairingCode)
                    }
                }
                if (connected) {
                    WirelessAdbNotifications.showResult(
                        this@WirelessAdbPairingService,
                        connected = true,
                        detail = getString(R.string.wireless_debugging_notification_ready),
                    )
                } else {
                    WirelessAdbNotifications.showPairingPrompt(
                        this@WirelessAdbPairingService,
                        getString(R.string.error_wireless_adb_unavailable),
                    )
                }
            } catch (error: Throwable) {
                WirelessAdbNotifications.showPairingPrompt(
                    this@WirelessAdbPairingService,
                    error.message ?: getString(R.string.error_wireless_adb_unavailable),
                )
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1206
        private const val PAIRING_TIMEOUT_MILLIS = 45_000L
    }
}
