package com.example.allinoneflushapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.FileInputStream

class AppMonitorVPNService : VpnService() {
    companion object {
        private var pandaActive = false
        private var instance: AppMonitorVPNService? = null

        fun isPandaActive() = pandaActive
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwardingActive = false
    private lateinit var wakeLock: PowerManager.WakeLock
    
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        
        // Ambil wake lock
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CBVPN:WakeLock")
        wakeLock.acquire(10 * 60 * 1000L)
        
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("CB Monitor Starting", true))

        Thread {
            try {
                setupVPN()
            } catch (e: Exception) {
                android.util.Log.e("CBVPN", "VPN Failed: ${e.message}")
                stopSelf()
            }
        }.start()
        
        return START_STICKY
    }

    private fun setupVPN() {
        val builder = Builder()
        builder.setSession("CB Monitor")
            .setMtu(1280)
            .addAddress("192.168.50.1", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.4.4")
        
        // Realme workaround
        try {
            builder.javaClass.getMethod("setBlocking", Boolean::class.java)
                .invoke(builder, false)
        } catch (e: Exception) {}
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                builder.javaClass.getMethod("setAllowBypass", Boolean::class.java)
                    .invoke(builder, true)
            } catch (e: Exception) {}
        }
        
        // Exclude semua apps kecuali Panda
        val packages = packageManager.getInstalledPackages(0)
        val pandaPackage = "com.logistics.rider.foodpanda"
        
        for (pkg in packages) {
            if (pkg.packageName != pandaPackage && pkg.packageName != packageName) {
                try {
                    builder.addDisallowedApplication(pkg.packageName)
                } catch (e: Exception) {}
            }
        }

        vpnInterface = builder.establish()
        if (vpnInterface != null) {
            forwardingActive = true
            pandaActive = true
            
            startTrafficMonitor()
            updateNotification("VPN Active - Panda Hijau")
            android.util.Log.d("CBVPN", "VPN Established Successfully")
        } else {
            stopSelf()
        }
    }

    private fun startTrafficMonitor() {
        Thread {
            val buffer = ByteArray(2048)
            
            try {
                val fd = vpnInterface?.fileDescriptor ?: return@Thread
                val input = FileInputStream(fd)
                
                while (forwardingActive) {
                    val len = input.read(buffer)
                    if (len > 0) {
                        pandaActive = true
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CBVPN", "Traffic monitor error")
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, 
                "CB Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "VPN monitoring service"
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String, connected: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) 
                PendingIntent.FLAG_IMMUTABLE else 0
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CB Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notification = createNotification(text, true)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, notification)
    }

    override fun onDestroy() {
        forwardingActive = false
        vpnInterface?.close()
        vpnInterface = null
        pandaActive = false
        instance = null
        
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        
        stopForeground(true)
        super.onDestroy()
    }
}
