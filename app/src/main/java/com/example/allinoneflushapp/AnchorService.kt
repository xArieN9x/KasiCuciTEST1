package com.example.allinoneflushapp

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AnchorService : Service() {

    private var pingJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        // Create notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "cb_anchor_channel"
            val channel = NotificationChannel(
                channelId,
                "CedokBooster Anchor",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        // Build notification
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "cb_anchor_channel")
                .setContentTitle("Cedok Booster Active")
                .setContentText("Stabilizing connection…")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Cedok Booster Active")
                .setContentText("Stabilizing connection…")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        }

        startForeground(77, notif)

        // ✅ Start lightweight background ping
        startBackgroundPing()
    }

    private fun startBackgroundPing() {
        pingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    // Send tiny UDP packet to Cloudflare DNS
                    val socket = DatagramSocket()
                    val payload = byteArrayOf(0x00) // 1 byte only
                    val packet = DatagramPacket(
                        payload,
                        payload.size,
                        InetAddress.getByName("1.1.1.1"),
                        53
                    )
                    socket.send(packet)
                    socket.close()
                } catch (e: Exception) {
                    // Fail silently — jangan ganggu
                }
                delay(12000) // Setiap 12 saat
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        pingJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
