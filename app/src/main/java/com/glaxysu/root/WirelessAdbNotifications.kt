package com.glaxysu.root

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat

object WirelessAdbNotifications {
    const val ACTION_PAIR = "com.glaxysu.root.action.PAIR_WIRELESS_ADB"
    const val EXTRA_PAIRING_CODE = "pairing_code"

    private const val CHANNEL_ID = "wireless_debugging"
    private const val NOTIFICATION_ID = 1206

    fun showPairingPrompt(context: Context, detail: String? = null) {
        val appContext = context.applicationContext
        createChannel(appContext)
        val remoteInput = RemoteInput.Builder(EXTRA_PAIRING_CODE)
            .setLabel(appContext.getString(R.string.wireless_debugging_pair_code))
            .build()
        val intent = Intent(appContext, WirelessAdbPairingReceiver::class.java)
            .setAction(ACTION_PAIR)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(
            null,
            appContext.getString(R.string.wireless_debugging_notification_submit),
            pendingIntent,
        ).addRemoteInput(remoteInput).build()
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kernelsu)
            .setContentTitle(appContext.getString(R.string.wireless_debugging_notification_title))
            .setContentText(detail ?: appContext.getString(R.string.wireless_debugging_notification_body))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build()
        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
    }

    internal fun buildPairingProgressNotification(context: Context): Notification {
        val appContext = context.applicationContext
        createChannel(appContext)
        return Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kernelsu)
            .setContentTitle(appContext.getString(R.string.wireless_debugging_notification_title))
            .setContentText(appContext.getString(R.string.wireless_debugging_notification_pairing))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun showResult(context: Context, connected: Boolean, detail: String? = null) {
        val appContext = context.applicationContext
        createChannel(appContext)
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kernelsu)
            .setContentTitle(
                appContext.getString(
                    if (connected) {
                        R.string.wireless_debugging_notification_connected
                    } else {
                        R.string.wireless_debugging_notification_failed
                    },
                ),
            )
            .setContentText(detail ?: appContext.getString(R.string.wireless_debugging_notification_body))
            .setOnlyAlertOnce(true)
            .setOngoing(!connected)
            .build()
        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.wireless_debugging),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.wireless_debugging_description)
            },
        )
    }
}

class WirelessAdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pairingCode = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(WirelessAdbNotifications.EXTRA_PAIRING_CODE)
            ?.toString()
            ?.filter(Char::isDigit)
            ?.take(6)
            .orEmpty()
        val serviceIntent = Intent(context, WirelessAdbPairingService::class.java)
            .putExtra(WirelessAdbNotifications.EXTRA_PAIRING_CODE, pairingCode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
