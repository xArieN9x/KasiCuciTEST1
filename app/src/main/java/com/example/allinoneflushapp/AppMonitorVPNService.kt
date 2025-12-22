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
import java.io.FileOutputStream

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
    
        // ✅ REALME C3 COMPATIBLE CONFIG (NO ROOT)
        val builder = Builder()
        builder.setSession("CBTunnel")
            .setMtu(1500)
            .addAddress("10.215.173.2", 30)
            
            // ⭐ CRITICAL: REALME ACCEPTS THESE ROUTES
            .addRoute("0.0.0.0", 1)      // 0.0.0.0 - 127.255.255.255
            .addRoute("128.0.0.0", 1)    // 128.0.0.0 - 255.255.255.255
            
            // Add common subnets untuk trigger routing
            .addRoute("1.0.0.0", 8)      // Major cloud providers
            .addRoute("8.0.0.0", 7)      // Google, Cloudflare
            .addRoute("13.0.0.0", 8)     // Microsoft
            .addRoute("17.0.0.0", 8)     // Apple
            .addRoute("23.0.0.0", 8)     // AT&T
            .addRoute("31.0.0.0", 8)     // Vodafone
            .addRoute("37.0.0.0", 8)     // Telecom Italia
            .addRoute("45.0.0.0", 8)     // Rogers
            .addRoute("49.0.0.0", 8)     // NTT
            .addRoute("64.0.0.0", 2)     // Major US networks
            
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addDnsServer("208.67.222.222")
    
        // Apply Realme workarounds
        applyRealmeWorkaround(builder)
    
        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
    
        if (vpnInterface != null) {
            forwardingActive = true
            
            // Log untuk debug
            logCurrentRoutes()
            
            // Start forwarder
            startPacketForwarder()
        } else {
            stopSelf()
        }
    
        return START_STICKY
    }

    // ✅ REALME SPECIFIC WORKAROUNDS
    private fun applyRealmeWorkaround(builder: Builder) {
        try {
            // Disable IPv6 (Realme sering ada issue dengan IPv6)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val setMetered = builder.javaClass.getMethod("setMetered", Boolean::class.java)
                setMetered.invoke(builder, false)
            }
        } catch (e: Exception) {}
        
        try {
            // Set blocking mode
            val setBlocking = builder.javaClass.getMethod("setBlocking", Boolean::class.java)
            setBlocking.invoke(builder, true)
        } catch (e: Exception) {}
        
        try {
            // Allow bypass (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val setAllowBypass = builder.javaClass.getMethod("setAllowBypass", Boolean::class.java)
                setAllowBypass.invoke(builder, true)
            }
        } catch (e: Exception) {}
    }
    
    private fun logCurrentRoutes() {
        Thread {
            try {
                Thread.sleep(2000) // Tunggu interface ready
                
                val commands = arrayOf(
                    arrayOf("ip", "route", "show", "dev", "tun1"),
                    arrayOf("ip", "-6", "route", "show", "dev", "tun1"),
                    arrayOf("ip", "route", "show", "table", "all")
                )
                
                for (cmd in commands) {
                    try {
                        val process = Runtime.getRuntime().exec(cmd)
                        val output = process.inputStream.bufferedReader().readText()
                        android.util.Log.d("CB_VPN", "CMD: ${cmd.joinToString(" ")}\n$output")
                    } catch (e: Exception) {
                        android.util.Log.e("CB_VPN", "Cmd failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "logCurrentRoutes error: ${e.message}")
            }
        }.start()
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

    // Keep for compatibility
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

    private fun startPacketForwarder() {
        Thread {
            val buffer = ByteArray(32767)
            
            try {
                val fd = vpnInterface?.fileDescriptor ?: return@Thread
                val input = FileInputStream(fd)
                val output = FileOutputStream(fd)
                
                android.util.Log.d("CB_VPN", "Packet forwarder started")
                
                while (forwardingActive) {
                    val len = input.read(buffer)
                    if (len <= 0) continue
                    
                    android.util.Log.d("CB_VPN", "Forwarding $len bytes")
                    pandaActive = true
                    
                    output.write(buffer, 0, len)
                    output.flush()
                }
            } catch (e: Exception) {
                if (forwardingActive) {
                    android.util.Log.e("CB_VPN", "Forward error: ${e.message}")
                }
            } finally {
                cleanup()
            }
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
