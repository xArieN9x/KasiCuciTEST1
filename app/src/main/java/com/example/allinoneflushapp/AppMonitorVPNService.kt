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
        private var dnsIndex = 0
        private var instance: AppMonitorVPNService? = null

        fun isPandaActive() = pandaActive

        fun rotateDNS(dnsList: List<String>) {
            if (instance == null) return
            dnsIndex = (dnsIndex + 1) % dnsList.size
            val nextDNS = dnsList[dnsIndex]
            instance?.establishVPN(nextDNS)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Panda Monitor running"))
        // initial estab with default dns
        establishVPN("8.8.8.8")
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Panda Monitor", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Panda Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_vpn)
            .setContentIntent(pi)
            .build()
    }

    fun establishVPN(dns: String) {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        val builder = Builder()
        builder.setSession("PandaMonitor")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAllowedApplication("com.logistics.rider.foodpanda")
            .addDnsServer(dns)
        vpnInterface = builder.establish()
        // start monitoring
        monitorTraffic()
    }

    private fun monitorTraffic() {
        Thread {
            while (true) {
                try {
                    val fd = vpnInterface?.fileDescriptor
                    if (fd == null) {
                        pandaActive = false
                        Thread.sleep(1000); continue
                    }
                    val input = FileInputStream(fd)
                    val available = try { input.available() } catch (_: Exception) { 0 }
                    pandaActive = available > 0
                } catch (e: Exception) {
                    pandaActive = false
                }
                Thread.sleep(1000)
            }
        }.start()
    }

    override fun onDestroy() {
        try { vpnInterface?.close() } catch (_: Exception) {}
        pandaActive = false
        instance = null
        super.onDestroy()
    }
}
