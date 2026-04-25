package com.instacapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * SyncForegroundService — foreground service для фоновой работы.
 * Android 8+ (API 26) требует уведомление при запуске foreground service.
 * Используется как fallback для фоновой синхронизации очереди.
 */
class SyncForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "instacapture_sync_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Выполняем синхронизацию
        val networkManager = NetworkManager(this)
        networkManager.syncQueue()

        // Останавливаем сервис после синхронизации
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "InstaCapture Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновая синхронизация данных"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("InstaCapture")
            .setContentText("Синхронизация данных...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
