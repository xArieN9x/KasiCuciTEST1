package com.example.allinoneflushapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream

class AppMonitorVPNService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwardingActive = false
    private val CHANNEL_ID = "cb_vpn_monitor"
    private val NOTIF_ID = 999

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("CB Monitor Active", true))
        
        // Setup VPN — semua app lalu, DNS = 1.1.1.1
        val builder = Builder()
        builder.setSession("CBMonitor")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1") // ✅ Hardcode Cloudflare DNS
        
        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }

        if (vpnInterface != null) {
            forwardingActive = true
            startPacketReader()
        } else {
            stopSelf()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val chan = NotificationChannel(CHANNEL_ID, "CB VPN Monitor", NotificationManager.IMPORTANCE_LOW)
            nm?.createNotificationChannel(chan)
        }
    }

    private fun createNotification(text: String, connected: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CB Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startPacketReader() {
        Thread {
            val buffer = ByteArray(2048)
            while (forwardingActive) {
                try {
                    val fd = vpnInterface?.fileDescriptor ?: break
                    val len = FileInputStream(fd).read(buffer)
                    if (len > 0) {
                        // ✅ LOG SIMPLE UNTUK ADB
                        android.util.Log.d("CB_VPN", "Packet captured: $len bytes")
                    }
                } catch (e: Exception) {
                    break
                }
            }
            vpnInterface?.close()
            stopForeground(true)
            stopSelf()
        }.start()
    }

    override fun onDestroy() {
        forwardingActive = false
        vpnInterface?.close()
        super.onDestroy()
    }
}
