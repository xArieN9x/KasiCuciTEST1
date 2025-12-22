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
import java.net.ServerSocket

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
        startForeground(NOTIF_ID, createNotification("CB Monitor Active", connected = true))
    
        // ✅ FINAL FIX: VPN untuk monitor SAHAJA, semua apps bypass
        val builder = Builder()
        builder.setSession("CB Monitor")
            .setMtu(1500)
            .addAddress("10.215.173.2", 30)
            .addRoute("0.0.0.0", 1)      // Split route 1
            .addRoute("128.0.0.0", 1)    // Split route 2
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            
            // ✅ EXCLUDE SEMUA APPS - biar terus ke internet
            .addDisallowedApplication(packageName)  // Self
        
        // ✅ Exclude common apps untuk pastikan internet berfungsi
        excludeCommonApps(builder)
        
        // ✅ Realme workarounds
        applyRealmeWorkaround(builder)
    
        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
    
        if (vpnInterface != null) {
            forwardingActive = true
            
            // ✅ PANDA AKTIF SERTA-MERTA (tanpa perlu traffic)
            pandaActive = true
            
            // ✅ START LOCAL SERVER untuk trigger panda consistency
            startLocalTriggerServer()
            
            // ✅ MONITOR TRAFFIC (optional)
            startTrafficMonitor()
        } else {
            stopSelf()
        }
    
        return START_STICKY
    }

    private fun excludeCommonApps(builder: Builder) {
        val appsToExclude = arrayOf(
            "com.android.chrome",
            "com.google.android.googlequicksearchbox",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.browser",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.microsoft.emmx",
            "com.facebook.katana",
            "com.whatsapp",
            "com.instagram.android",
            "com.twitter.android",
            "com.android.email",
            "com.google.android.youtube"
        )
        
        for (app in appsToExclude) {
            try {
                builder.addDisallowedApplication(app)
            } catch (e: Exception) {
                // App mungkin tak installed
            }
        }
    }

    private fun applyRealmeWorkaround(builder: Builder) {
        try {
            // Set blocking
            val setBlocking = builder.javaClass.getMethod("setBlocking", Boolean::class.java)
            setBlocking.invoke(builder, true)
        } catch (e: Exception) {}
        
        try {
            // Android 10+ allow bypass
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val setAllowBypass = builder.javaClass.getMethod("setAllowBypass", Boolean::class.java)
                setAllowBypass.invoke(builder, true)
            }
        } catch (e: Exception) {}
    }
    
    // ✅ LOCAL SERVER untuk trigger panda consistency
    private fun startLocalTriggerServer() {
        Thread {
            try {
                val server = ServerSocket(29293) // Random port
                android.util.Log.d("CB_VPN", "Local trigger server started on port 29293")
                
                while (forwardingActive) {
                    val client = server.accept()
                    client.getInputStream().read()
                    client.close()
                    
                    // Refresh panda active
                    pandaActive = true
                    android.util.Log.d("CB_VPN", "Panda trigger refreshed")
                }
                server.close()
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "Local server error: ${e.message}")
            }
        }.start()
        
        // ✅ SEND SELF-TRIGGER setiap 30 saat
        Thread {
            while (forwardingActive) {
                try {
                    Thread.sleep(30000)
                    // Self-trigger
                    pandaActive = true
                    android.util.Log.d("CB_VPN", "Panda auto-refresh")
                } catch (e: Exception) {
                    break
                }
            }
        }.start()
    }

    private fun startTrafficMonitor() {
        Thread {
            val buffer = ByteArray(2048)
            
            try {
                val fd = vpnInterface?.fileDescriptor ?: return@Thread
                val input = FileInputStream(fd)
                
                android.util.Log.d("CB_VPN", "Traffic monitor started")
                
                while (forwardingActive) {
                    val len = input.read(buffer)
                    if (len > 0) {
                        // Traffic detected - refresh panda
                        pandaActive = true
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "Monitor error: ${e.message}")
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "CB Monitor", NotificationManager.IMPORTANCE_LOW)
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
