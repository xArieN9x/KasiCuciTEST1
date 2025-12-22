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
    
        // ✅ FIXED CONFIG - REALME C3 COMPATIBLE
        val builder = Builder()
        builder.setSession("CBTunnel")
            .setMtu(1500)
            .addAddress("10.215.173.2", 30)
            .addRoute("0.0.0.0", 0)      // ✅ DEFAULT ROUTE SEMUA TRAFIK
            .addRoute("0.0.0.0", 1)      // ✅ COVER 0.0.0.0 - 127.255.255.255
            .addRoute("128.0.0.0", 1)    // ✅ COVER 128.0.0.0 - 255.255.255.255
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addDnsServer("208.67.222.222")  // OpenDNS backup
    
        // IPv6 (optional)
        try {
            builder.addAddress("fd00:2:fd00:1:fd00:1:fd00:2", 128)
            builder.addRoute("::", 0)    // ✅ DEFAULT ROUTE IPv6
            builder.addDnsServer("2606:4700:4700::1111")
        } catch (e: Exception) {
            // Skip jika tak support
        }
    
        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
    
        if (vpnInterface != null) {
            forwardingActive = true
            
            // ✅ TAMBAH MANUAL DEFAULT ROUTE VIA ip COMMAND
            addManualDefaultRoute()
            
            startPacketForwarder()
        } else {
            stopSelf()
        }
    
        return START_STICKY
    }

    // ✅ FUNCTION TAMBAHAN: ADD MANUAL DEFAULT ROUTE
    private fun addManualDefaultRoute() {
        Thread {
            try {
                // Tunggu 1 saat untuk interface stabil
                Thread.sleep(1000)
                
                // Guna ip command untuk tambah default route
                val commands = arrayOf(
                    arrayOf("ip", "route", "add", "default", "dev", "tun1"),
                    arrayOf("ip", "-6", "route", "add", "default", "dev", "tun1")
                )
                
                for (cmd in commands) {
                    try {
                        val process = ProcessBuilder(*cmd).start()
                        val exitCode = process.waitFor()
                        if (exitCode == 0) {
                            android.util.Log.d("CB_VPN", "Success: ${cmd.joinToString(" ")}")
                        } else {
                            android.util.Log.w("CB_VPN", "Failed (${exitCode}): ${cmd.joinToString(" ")}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("CB_VPN", "Command error: ${e.message}")
                    }
                }
                
                // Log current routes untuk debug
                val routeProcess = ProcessBuilder("ip", "route", "show", "dev", "tun1").start()
                val input = routeProcess.inputStream.bufferedReader()
                val routes = input.readText()
                android.util.Log.d("CB_VPN", "Current tun1 routes:\n$routes")
                
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "addManualDefaultRoute error: ${e.message}")
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

    // ⬇️ KEEP FUNCTION INI UNTUK COMPATIBILITY
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

    // ⬇️ PACKET FORWARDER UTAMA
    private fun startPacketForwarder() {
        Thread {
            val buffer = ByteArray(32767) // Buffer lebih besar
            
            try {
                val fd = vpnInterface?.fileDescriptor ?: return@Thread
                val input = FileInputStream(fd)
                val output = FileOutputStream(fd)
                
                android.util.Log.d("CB_VPN", "Packet forwarder started")
                
                while (forwardingActive) {
                    val len = input.read(buffer)
                    if (len <= 0) continue
                    
                    // Log untuk debug
                    android.util.Log.d("CB_VPN", "Forwarding $len bytes")
                    pandaActive = true
                    
                    // ✅ FORWARD PACKET BALIK KE INTERNET
                    // Packet dari apps → tun1 → forward ke internet
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
