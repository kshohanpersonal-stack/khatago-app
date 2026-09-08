package com.khatago.finance.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.khatago.finance.MainActivity
import com.khatago.finance.R

/**
 * Local notifications: one low-key channel, no sounds beyond the system default, no badge spam.
 *
 * Design decisions worth their documentation:
 *  - **A single channel** (`reminders`). Multiple channels tempt an app into posting multiple
 *    notifications per day. One channel also means one switch in system settings, which is what a
 *    daily ledger reminder deserves.
 *  - **`IMPORTANCE_DEFAULT`, not HIGH.** A due date is not an emergency, and KhataGo must never
 *    compete with a phone call for attention.
 *  - **Description text states the privacy property.** The channel description is the sentence a
 *    user reads when deciding whether to allow notifications, so it says plainly that nothing is
 *    sent anywhere.
 *  - The notification body never contains an amount. A lock-screen preview that reads "you owe
 *    ৳42,000 to Karim Bhaban" is information about someone's finances handed to whoever is looking
 *    at the phone. The copy is "1 payment is due today — open KhataGo for the detail."
 */
object Notifications {

    const val CHANNEL_REMINDERS = "khatago.reminders"
    const val CHANNEL_ID = CHANNEL_REMINDERS

    /** Stable ids so an update replaces the same notification instead of stacking. */
    const val ID_DAILY_REMINDER = 1001
    const val ID_DUE_TODAY = 1002
    const val ID_OVERDUE = 1003

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_REMINDERS)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            "KhataGo reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Daily due-date reminders. Created on this phone; nothing is sent anywhere."
            enableLights(false)
            enableVibration(true)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * `id` doubles as the PendingIntent request code, which is what makes re-posting the same
     * category replace the previous notification instead of adding one.
     */
    fun post(
        context: Context,
        id: Int,
        title: String,
        text: String,
        deepLink: String? = null,
    ) {
        if (!hasPermission(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (deepLink != null) {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse(deepLink)
            }
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_khatago)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun cancelAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        runCatching { manager.cancel(ID_DAILY_REMINDER) }
        runCatching { manager.cancel(ID_DUE_TODAY) }
        runCatching { manager.cancel(ID_OVERDUE) }
    }
}
