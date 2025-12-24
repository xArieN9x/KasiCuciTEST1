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
        
        // ✅ HANYA exclude Chrome untuk test (Panda MASUK VPN)
        try {
            builder.addDisallowedApplication("com.android.chrome")
            android.util.Log.d("CB_VPN", "Chrome EXCLUDED")
        } catch (e: Exception) {}
        
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
            startTun2Socks()
            pandaActive = true
            
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, createNotification("CB Monitor Active ✅", true))
            android.util.Log.d("CB_VPN", "✅ VPN STARTED")
        } else {
            stopSelf()
        }
    
        return START_STICKY
    }
    
    private fun startTun2Socks() {
        Thread {
            try {
                val tunFd = vpnInterface?.fd ?: -1
                android.util.Log.d("CB_VPN", "TUN FD: $tunFd")
                
                val bin = extractBinary()
                if (bin == null) {
                    android.util.Log.e("CB_VPN", "Binary extraction FAILED")
                    return@Thread
                }
                
                android.util.Log.d("CB_VPN", "Binary: ${bin.absolutePath}")
                
                // ✅ CORRECT command for xjasonlyu/tun2socks
                val cmd = arrayOf(
                    bin.absolutePath,
                    "-device", "tun://${vpnInterface!!.fd}",
                    "-proxy", "direct://",
                    "-loglevel", "info"
                )
                
                android.util.Log.d("CB_VPN", "CMD: ${cmd.joinToString(" ")}")
                
                tun2socksProcess = ProcessBuilder(*cmd)
                    .redirectErrorStream(true)
                    .start()
                
                android.util.Log.d("CB_VPN", "✅ tun2socks STARTED")
                
                // Monitor output
                val reader = BufferedReader(InputStreamReader(tun2socksProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    android.util.Log.d("CB_TUN2SOCKS", line!!)
                    pandaActive = true
                }
                
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "tun2socks ERROR: ${e.message}")
                e.printStackTrace()
            }
        }.start()
        
        Thread {
            while (forwardingActive) {
                try {
                    Thread.sleep(30000)
                    android.util.Log.d("CB_VPN", "💚 Heartbeat")
                } catch (e: Exception) {
                    break
                }
            }
        }.start()
    }
    
    private fun extractBinary(): File? {
        return try {
            val abi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Build.SUPPORTED_ABIS[0]
            } else {
                Build.CPU_ABI
            }
            
            val name = when {
                abi.contains("arm64") -> "tun2socks_arm64"
                abi.contains("armeabi") -> "tun2socks_arm"
                else -> "tun2socks_arm64"
            }
            
            android.util.Log.d("CB_VPN", "ABI: $abi, Binary: $name")
            
            val out = File(cacheDir, "tun2socks")
            assets.open(name).use { inp ->
                FileOutputStream(out).use { outp ->
                    inp.copyTo(outp)
                }
            }
            
            out.setExecutable(true, false)
            out.setReadable(true, false)
            
            android.util.Log.d("CB_VPN", "✅ Extracted: ${out.length()} bytes")
            out
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "Extract ERROR: ${e.message}")
            null
        }
    }
    
    private fun applyRealmeWorkaround(builder: Builder) {
        try {
            builder.javaClass.getMethod("setBlocking", Boolean::class.java)
                .invoke(builder, false)
        } catch (e: Exception) {}
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.javaClass.getMethod("setAllowBypass", Boolean::class.java)
                    .invoke(builder, true)
            }
        } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val chan = NotificationChannel(CHANNEL_ID, "CB Monitor", NotificationManager.IMPORTANCE_LOW)
            chan.setShowBadge(false)
            nm?.createNotificationChannel(chan)
        }
    }

    private fun createNotification(text: String, connected: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) 
                PendingIntent.FLAG_IMMUTABLE else 0
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CB Panda Monitor")
            .setContentText(text)
            .setSmallIcon(if (connected) android.R.drawable.presence_online 
                         else android.R.drawable.presence_busy)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun cleanup() {
        forwardingActive = false
        tun2socksProcess?.destroy()
        vpnInterface?.close()
        pandaActive = false
        instance = null
        stopForeground(true)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
