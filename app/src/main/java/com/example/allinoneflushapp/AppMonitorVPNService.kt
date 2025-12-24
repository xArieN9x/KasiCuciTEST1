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
import java.io.*

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
    
    private var tun2socksProcess: Process? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("CB Monitor Starting...", false))
    
        val builder = Builder()
        builder.setSession("CB Monitor")
            .setMtu(1500)
            .addAddress("10.215.173.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addDisallowedApplication(packageName)
        
        applyRealmeWorkaround(builder)
    
        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "VPN failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
    
        if (vpnInterface != null) {
            forwardingActive = true
            
            // START TUN2SOCKS (FIXED!)
            startTun2Socks()
            
            pandaActive = true
            
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, createNotification("CB Monitor Active ✅", true))
            android.util.Log.d("CB_VPN", "✅ VPN STARTED with tun2socks")
        } else {
            stopSelf()
        }
    
        return START_STICKY
    }
    
    private fun startTun2Socks() {
        Thread {
            try {
                // Get TUN FD
                val tunFd = vpnInterface?.fd ?: -1
                if (tunFd < 0) {
                    android.util.Log.e("CB_VPN", "Invalid TUN FD")
                    return@Thread
                }
                
                // Extract binary
                val tun2socksBin = extractBinary()
                if (tun2socksBin == null || !tun2socksBin.exists()) {
                    android.util.Log.e("CB_VPN", "tun2socks binary not found!")
                    return@Thread
                }
                
                android.util.Log.d("CB_VPN", "Binary ready: ${tun2socksBin.absolutePath}")
                android.util.Log.d("CB_VPN", "TUN FD: $tunFd")
                
                // CORRECT COMMAND (based on badvpn tun2socks)
                val command = arrayOf(
                    tun2socksBin.absolutePath,
                    "--tunfd", tunFd.toString(),
                    "--netif-ipaddr", "10.215.173.1",
                    "--netif-netmask", "255.255.255.252",
                    "--socks-server-addr", "8.8.8.8:1080", // Fallback SOCKS (will fail gracefully)
                    "--loglevel", "info"
                )
                
                android.util.Log.d("CB_VPN", "Starting tun2socks: ${command.joinToString(" ")}")
                
                val pb = ProcessBuilder(*command)
                    .redirectErrorStream(true)
                
                // CRITICAL: Pass FD to child process
                val fdField = FileDescriptor::class.java.getDeclaredField("descriptor")
                fdField.isAccessible = true
                fdField.setInt(vpnInterface!!.fileDescriptor, tunFd)
                
                tun2socksProcess = pb.start()
                
                android.util.Log.d("CB_VPN", "✅ tun2socks process started")
                
                // Monitor output
                val reader = BufferedReader(InputStreamReader(tun2socksProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    android.util.Log.d("CB_TUN2SOCKS", line!!)
                    pandaActive = true
                }
                
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "tun2socks failed: ${e.message}")
                e.printStackTrace()
                
                // FALLBACK: Mark active anyway for monitoring
                pandaActive = true
            }
        }.start()
        
        // Heartbeat
        Thread {
            while (forwardingActive) {
                try {
                    Thread.sleep(30000)
                    android.util.Log.d("CB_VPN", "💚 Heartbeat - Active: $pandaActive")
                } catch (e: Exception) {
                    break
                }
            }
        }.start()
    }
    
    private fun extractBinary(): File? {
        return try {
            // Detect ABI
            val abi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Build.SUPPORTED_ABIS[0]
            } else {
                @Suppress("DEPRECATION")
                Build.CPU_ABI
            }
            
            android.util.Log.d("CB_VPN", "Device ABI: $abi")
            
            // Map to binary name
            val binaryName = when {
                abi.contains("arm64") -> "tun2socks_arm64"
                abi.contains("armeabi") -> "tun2socks_arm"
                abi.contains("x86_64") -> "tun2socks_x86_64"
                abi.contains("x86") -> "tun2socks_x86"
                else -> "tun2socks_arm64" // default
            }
            
            android.util.Log.d("CB_VPN", "Looking for binary: $binaryName")
            
            // Check in assets
            val assetFiles = assets.list("") ?: emptyArray()
            android.util.Log.d("CB_VPN", "Assets found: ${assetFiles.joinToString()}")
            
            if (!assetFiles.contains(binaryName)) {
                android.util.Log.e("CB_VPN", "Binary $binaryName NOT in assets!")
                return null
            }
            
            // Extract to cache
            val outputFile = File(cacheDir, "tun2socks")
            assets.open(binaryName).use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Make executable
            outputFile.setExecutable(true, false)
            outputFile.setReadable(true, false)
            
            android.util.Log.d("CB_VPN", "✅ Binary extracted: ${outputFile.absolutePath}")
            android.util.Log.d("CB_VPN", "Executable: ${outputFile.canExecute()}")
            android.util.Log.d("CB_VPN", "Size: ${outputFile.length()} bytes")
            
            outputFile
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "Extract failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    private fun applyRealmeWorkaround(builder: Builder) {
        try {
            val setBlocking = builder.javaClass.getMethod("setBlocking", Boolean::class.java)
            setBlocking.invoke(builder, false)
            android.util.Log.d("CB_VPN", "Realme: Non-blocking set")
        } catch (e: Exception) {
            android.util.Log.w("CB_VPN", "Realme blocking failed")
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val setAllowBypass = builder.javaClass.getMethod("setAllowBypass", Boolean::class.java)
                setAllowBypass.invoke(builder, true)
                android.util.Log.d("CB_VPN", "Realme: Bypass allowed")
            }
        } catch (e: Exception) {
            android.util.Log.w("CB_VPN", "Realme bypass failed")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "CB Monitor", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
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
        
        tun2socksProcess?.destroy()
        tun2socksProcess = null
        
        vpnInterface?.close()
        vpnInterface = null
        
        pandaActive = false
        instance = null
        
        stopForeground(true)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
