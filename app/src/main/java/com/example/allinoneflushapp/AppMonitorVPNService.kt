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
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket

class AppMonitorVPNService : VpnService() {
    companion object {
        private var pandaActive = false
        private var instance: AppMonitorVPNService? = null

        fun isPandaActive() = pandaActive
        fun rotateDNS(dnsList: List<String>) {}
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwardingActive = false
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("CB Monitor Active", connected = true))
        
        android.util.Log.d("CB_VPN", "=== VPN SERVICE START ===")
    
        // Setup VPN Builder
        val builder = Builder()
        builder.setSession("CB Monitor")
            .setMtu(1500)
            .addAddress("10.215.173.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDisallowedApplication(packageName)
    
        vpnInterface = try {
            val iface = builder.establish()
            android.util.Log.d("CB_VPN", "VPN Interface established")
            iface
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "VPN establish error: ${e.message}")
            null
        }
    
        if (vpnInterface != null) {
            forwardingActive = true
            
            // TEST: Start simple tun2socks test
            testTun2Socks()
            
            pandaActive = true
            
            // Keep existing auto-refresh (biar ada log)
            startLocalTriggerServer()
            
            android.util.Log.d("CB_VPN", "=== VPN SERVICE READY ===")
        } else {
            stopSelf()
        }
    
        return START_STICKY
    }
    
    private fun testTun2Socks() {
        Thread {
            android.util.Log.d("CB_VPN", "testTun2Socks() started")
            
            // 1. Check assets
            try {
                val assets = assets.list("")
                android.util.Log.d("CB_VPN", "Assets: ${assets?.joinToString()}")
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "Assets error: ${e.message}")
            }
            
            // 2. Try to extract binary
            val binary = extractBinary()
            if (binary == null) {
                android.util.Log.e("CB_VPN", "Binary extraction FAILED")
                return@Thread
            }
            
            android.util.Log.d("CB_VPN", "Binary ready: ${binary.absolutePath}")
            
            // 3. Try to run
            try {
                val process = ProcessBuilder(binary.absolutePath, "--help")
                    .redirectErrorStream(true)
                    .start()
                
                Thread.sleep(1000)
                
                if (process.isAlive) {
                    android.util.Log.d("CB_VPN", "tun2socks process RUNNING")
                    process.destroy()
                } else {
                    android.util.Log.e("CB_VPN", "tun2socks process DIED")
                }
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "Run error: ${e.message}")
            }
        }.start()
    }
    
    private fun extractBinary(): File? {
        return try {
            val assetName = "tun2socks_arm64"
            android.util.Log.d("CB_VPN", "Opening asset: $assetName")
            
            val input = assets.open(assetName)
            val outputFile = File(cacheDir, "t2s_test")
            val output = FileOutputStream(outputFile)
            
            input.copyTo(output)
            input.close()
            output.close()
            
            outputFile.setExecutable(true)
            
            android.util.Log.d("CB_VPN", "Extracted: ${outputFile.length()} bytes")
            outputFile
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "Extract error: ${e.message}")
            null
        }
    }
    
    private fun startLocalTriggerServer() {
        Thread {
            try {
                val server = ServerSocket(29293)
                android.util.Log.d("CB_VPN", "Local server on 29293")
                
                while (forwardingActive) {
                    val client = server.accept()
                    client.getInputStream().read()
                    client.close()
                    pandaActive = true
                    android.util.Log.d("CB_VPN", "Panda refresh triggered")
                }
            } catch (e: Exception) {}
        }.start()
        
        Thread {
            while (forwardingActive) {
                try {
                    Thread.sleep(30000)
                    pandaActive = true
                } catch (e: Exception) { break }
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
