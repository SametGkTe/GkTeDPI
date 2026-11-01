package com.sametgkte.gktedpi.utility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.sametgkte.gktedpi.R
import com.sametgkte.gktedpi.activities.MainActivity
import com.sametgkte.gktedpi.data.RESTART_ACTION
import com.sametgkte.gktedpi.data.STOP_ACTION
import java.util.Locale

fun registerNotificationChannel(context: Context, id: String, @StringRes name: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val channel = NotificationChannel(
            id,
            context.getString(name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
        }

        manager.createNotificationChannel(channel)
    }
}

fun createConnectionNotification(
    context: Context,
    channelId: String,
    @StringRes title: Int,
    contentText: String,
    service: Class<*>,
): Notification =
    NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setSilent(true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentTitle(context.getString(title))
        .setContentText(contentText)
        .addAction(
            0,
            context.getString(R.string.notification_stop),
            PendingIntent.getService(
                context,
                1,
                Intent(context, service).setAction(STOP_ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .addAction(
            0,
            context.getString(R.string.notification_restart),
            PendingIntent.getService(
                context,
                2,
                Intent(context, service).setAction(RESTART_ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec.coerceAtLeast(0L))}/s"
