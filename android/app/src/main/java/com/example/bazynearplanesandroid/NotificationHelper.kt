package com.example.bazynearplanesandroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {
    const val CHANNEL_ID = "nearplanes_alerts"
    @Volatile var lastStatus: String = "init"

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Near planes",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Proximity alerts and test notifications"
                enableVibration(true)
                setShowBadge(true)
            }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    fun notify(ctx: Context, title: String, text: String, id: Int = 1001) {
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}
