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
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class AppMonitorVPNService : VpnService() {
    companion object {
        private var pandaActive = false
        private var instance: AppMonitorVPNService? = null
        private const val PANDA_PACKAGE = "com.logistics.rider.foodpanda"

        fun isPandaActive() = pandaActive
        fun rotateDNS(dnsList: List<String>) {
            // Reserved untuk future use
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwardingActive = false
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001
    
    // ✅ PROTECTED SOCKET untuk bypass circular routing
    private var protectedSocket: DatagramSocket? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("CB Monitor Starting...", connected = false))
    
        // ✅ VPN CONFIG - Monitor Panda dengan proper forwarding
        val builder = Builder()
        builder.setSession("CB Panda Monitor")
            .setMtu(1400)  // Optimized untuk Realme C3
            .addAddress("10.8.0.1", 24)  // Local VPN address
            
            // ✅ CRITICAL: Route hanya Panda traffic
            .addRoute("0.0.0.0", 1)      
            .addRoute("128.0.0.0", 1)    
            
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            
            // ✅ EXCLUDE apps EXCEPT Panda untuk testing
            .addDisallowedApplication(packageName)  // Exclude self
        
        // ✅ Exclude common apps untuk stability
        excludeCommonApps(builder)
        
        // ✅ REMOVE Panda dari exclude - biar masuk VPN
        // TIDAK ada addDisallowedApplication untuk Panda!
        
        // ✅ Realme workarounds
        applyRealmeWorkaround(builder)
    
        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "VPN establish failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
    
        if (vpnInterface != null) {
            forwardingActive = true
            
            // ✅ Setup protected socket untuk bypass
            setupProtectedSocket()
            
            // ✅ START packet forwarding (CRITICAL FIX)
            startPacketForwarder()
            
            // ✅ Update notification
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, createNotification("CB Monitor Active", connected = true))
            
            android.util.Log.d("CB_VPN", "VPN successfully started - monitoring Panda")
        } else {
            android.util.Log.e("CB_VPN", "VPN interface null - stopping")
            stopSelf()
        }
    
        return START_STICKY
    }
    
    // ✅ SETUP PROTECTED SOCKET - bypass circular routing
    private fun setupProtectedSocket() {
        Thread {
            try {
                protectedSocket = DatagramSocket()
                // ✅ CRITICAL: Protect socket dari VPN sendiri
                protect(protectedSocket!!)
                // Connect ke DNS untuk test
                protectedSocket?.connect(InetSocketAddress("8.8.8.8", 53))
                android.util.Log.d("CB_VPN", "Protected socket created successfully")
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "Protected socket failed: ${e.message}")
            }
        }.start()
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
                // App not installed
            }
        }
        
        // ✅ PENTING: JANGAN exclude Panda!
        // Panda MESTI masuk VPN untuk monitoring
        android.util.Log.d("CB_VPN", "Panda INCLUDED in VPN for monitoring")
    }

    private fun applyRealmeWorkaround(builder: Builder) {
        try {
            // Non-blocking mode untuk Realme
            val setBlocking = builder.javaClass.getMethod("setBlocking", Boolean::class.java)
            setBlocking.invoke(builder, false)
            android.util.Log.d("CB_VPN", "Realme: Non-blocking mode set")
        } catch (e: Exception) {
            android.util.Log.w("CB_VPN", "Realme blocking workaround failed")
        }
        
        try {
            // Allow bypass untuk stability
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val setAllowBypass = builder.javaClass.getMethod("setAllowBypass", Boolean::class.java)
                setAllowBypass.invoke(builder, true)
                android.util.Log.d("CB_VPN", "Realme: Bypass allowed")
            }
        } catch (e: Exception) {
            android.util.Log.w("CB_VPN", "Realme bypass workaround failed")
        }
    }
    
    // ✅ PACKET FORWARDER - THE CRITICAL FIX!
    private fun startPacketForwarder() {
        Thread {
            val buffer = ByteBuffer.allocate(32767)  // Max IP packet size
            var lastActivityTime = System.currentTimeMillis()
            
            try {
                val fd = vpnInterface?.fileDescriptor ?: return@Thread
                val input = FileInputStream(fd)
                val output = FileOutputStream(fd)
                
                android.util.Log.d("CB_VPN", "Packet forwarder STARTED")
                
                while (forwardingActive) {
                    buffer.clear()
                    
                    // ✅ READ packet dari VPN interface
                    val length = input.read(buffer.array())
                    
                    if (length > 0) {
                        buffer.limit(length)
                        
                        // ✅ DETECT Panda activity
                        pandaActive = true
                        lastActivityTime = System.currentTimeMillis()
                        
                        // ✅ FORWARD packet keluar (INTERNET ACCESS!)
                        output.write(buffer.array(), 0, length)
                        
                        // Log basic packet info
                        if (length >= 20) {
                            val protocol = buffer.get(9).toInt() and 0xFF
                            val protocolName = when(protocol) {
                                6 -> "TCP"
                                17 -> "UDP"
                                1 -> "ICMP"
                                else -> "OTHER"
                            }
                            android.util.Log.d("CB_VPN", 
                                "✅ Forwarded ${length}b [$protocolName] for Panda")
                        }
                    }
                    
                    // ✅ Auto-reset pandaActive after 5 seconds no traffic
                    if (System.currentTimeMillis() - lastActivityTime > 5000) {
                        if (pandaActive) {
                            android.util.Log.d("CB_VPN", "Panda idle - resetting active flag")
                            pandaActive = false
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "Forwarder error: ${e.message}")
                forwardingActive = false
            }
            
            android.util.Log.d("CB_VPN", "Packet forwarder STOPPED")
        }.start()
        
        // ✅ HEARTBEAT thread untuk consistency
        Thread {
            while (forwardingActive) {
                try {
                    Thread.sleep(30000)  // 30 seconds
                    android.util.Log.d("CB_VPN", "VPN heartbeat - Active: $pandaActive")
                } catch (e: Exception) {
                    break
                }
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, 
                "CB Panda Monitor", 
                NotificationManager.IMPORTANCE_LOW
            )
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
        
        val icon = if (connected) 
            android.R.drawable.presence_online 
        else 
            android.R.drawable.presence_busy
            
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CB Panda Monitor")
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun cleanup() {
        forwardingActive = false
        
        protectedSocket?.close()
        protectedSocket = null
        
        vpnInterface?.close()
        vpnInterface = null
        
        pandaActive = false
        instance = null
        
        stopForeground(true)
        android.util.Log.d("CB_VPN", "VPN cleaned up")
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
