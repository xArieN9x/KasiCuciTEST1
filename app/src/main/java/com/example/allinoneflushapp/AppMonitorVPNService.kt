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
    companion object {
        private var pandaActive = false
        private var instance: AppMonitorVPNService? = null

        fun isPandaActive() = pandaActive
        fun rotateDNS(dnsList: List<String>) {
            // Disabled
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwardingActive = false
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("CB Tunnel Active", connected = true))
    
        // ✅ KONFIGURASI FIXED UNTUK REALME C3
        val builder = Builder()
        builder.setSession("CBTunnel")
            .setMtu(1500)
            .addAddress("10.215.173.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        // Allow bypass hanya untuk API 29+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setAllowBypass(true)
        }
    
        // IPv6 (optional)
        try {
            builder.addAddress("fd00:2:fd00:1:fd00:1:fd00:2", 128)
            builder.addRoute("::", 0)  // Default route IPv6
            builder.addDnsServer("2606:4700:4700::1111")
        } catch (e: Exception) {
            // Ignore jika device tak support IPv6
        }
    
        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
    
        if (vpnInterface != null) {
            forwardingActive = true
            startSimpleReader()
        } else {
            stopSelf()
        }
    
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "CB Tunnel", NotificationManager.IMPORTANCE_LOW)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String, connected: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val smallIcon = if (connected) android.R.drawable.presence_online else android.R.drawable.presence_busy
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CB Monitor")
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startSimpleReader() {
        Thread {
            val buffer = ByteArray(2048)
            while (forwardingActive) {
                try {
                    val fd = vpnInterface?.fileDescriptor ?: break
                    val len = FileInputStream(fd).read(buffer)
                    if (len > 0) {
                        android.util.Log.d("CB_VPN", "Traffic: $len bytes")
                        pandaActive = true
                    }
                } catch (e: Exception) {
                    break
                }
            }
            cleanup()
        }.start()
    }

    private fun cleanup() {
        forwardingActive = false
        vpnInterface?.close()
        vpnInterface = null
        pandaActive = false
        instance = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
