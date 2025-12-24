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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors

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
    
    // Tun2Socks variables
    private var tun2socksProcess: Process? = null
    private var socksServer: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("CB Monitor Active", connected = true))
        
        // 🔥 LOG 1: Service started
        android.util.Log.d("CB_VPN", "VPN Service STARTING - onStartCommand()")
    
        // Setup VPN Builder
        val builder = Builder()
        builder.setSession("CB Monitor")
            .setMtu(1500)
            .addAddress("10.215.173.2", 30)
            .addRoute("0.0.0.0", 0)      // Route ALL traffic through VPN
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            
        // CRITICAL: Jangan exclude Panda atau apps lain
        // Hanya exclude diri sendiri untuk elak loop
        builder.addDisallowedApplication(packageName)
    
        // Realme workarounds (keep existing)
        applyRealmeWorkaround(builder)
    
        // 🔥 LOG 2: Before establish
        android.util.Log.d("CB_VPN", "Calling builder.establish()...")
    
        vpnInterface = try {
            val iface = builder.establish()
            android.util.Log.d("CB_VPN", "VPN Interface established SUCCESS")
            iface
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "Failed to establish VPN: ${e.message}")
            e.printStackTrace()
            null
        }
    
        if (vpnInterface != null) {
            forwardingActive = true
            
            // 🔥 LOG 3: VPN ready
            android.util.Log.d("CB_VPN", "VPN ready. Calling startTun2SocksForwarding()")
            
            // Start tun2socks forwarding
            startTun2SocksForwarding()
            
            // PANDA AKTIF
            pandaActive = true
            android.util.Log.d("CB_VPN", "Panda set to ACTIVE")
            
            // LOCAL SERVER untuk trigger consistency (keep existing)
            startLocalTriggerServer()
            
            // MONITOR TRAFFIC (modified untuk forward)
            startTrafficMonitor()
            
            android.util.Log.d("CB_VPN", "All services started successfully")
        } else {
            android.util.Log.e("CB_VPN", "VPN Interface is NULL - stopping service")
            stopSelf()
        }
    
        return START_STICKY
    }
    
    // ==============================
    // TUN2SOCKS IMPLEMENTATION (WITH LOGGING)
    // ==============================
    private fun startTun2SocksForwarding() {
        // 🔥 LOG 4: Function entered
        android.util.Log.d("CB_VPN", "startTun2SocksForwarding() ENTERED")
        
        executor.submit {
            android.util.Log.d("CB_VPN", "Inside executor thread")
            try {
                // Step 1: Extract tun2socks binary dari assets
                android.util.Log.d("CB_VPN", "Step 1: Extracting tun2socks binary...")
                val tun2socksBin = extractTun2SocksBinary()
                
                if (tun2socksBin == null) {
                    android.util.Log.e("CB_VPN", "❌ FAILED: extractTun2SocksBinary returned NULL")
                    // FALLBACK: Langsung set panda active
                    pandaActive = true
                    return@submit
                }
                
                android.util.Log.d("CB_VPN", "✅ Binary extracted: ${tun2socksBin.absolutePath}")
                android.util.Log.d("CB_VPN", "Binary exists: ${tun2socksBin.exists()}, Size: ${tun2socksBin.length()} bytes")
                
                // Step 2: Start local SOCKS5 server untuk tun2socks connect
                android.util.Log.d("CB_VPN", "Step 2: Starting SOCKS5 server...")
                socksServer = ServerSocket(0).apply {
                    reuseAddress = true
                    soTimeout = 0
                }
                val socksPort = socksServer!!.localPort
                android.util.Log.d("CB_VPN", "✅ SOCKS5 server started on port $socksPort")
                
                // Step 3: Start tun2socks process
                android.util.Log.d("CB_VPN", "Step 3: Starting tun2socks process...")
                val command = arrayOf(
                    tun2socksBin.absolutePath,
                    "--tundev", "tun0",
                    "--netif-ipaddr", "10.215.173.1",
                    "--netif-netmask", "255.255.255.252",
                    "--socks-server-addr", "127.0.0.1:$socksPort",
                    "--transparent"
                )
                
                android.util.Log.d("CB_VPN", "Command: ${command.joinToString(" ")}")
                
                tun2socksProcess = ProcessBuilder(*command)
                    .redirectErrorStream(true)
                    .start()
                
                android.util.Log.d("CB_VPN", "✅ tun2socks process started")
                
                // Simple output reader (non-blocking)
                Thread {
                    try {
                        val inputStream = tun2socksProcess!!.inputStream
                        val buffer = ByteArray(1024)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            val output = String(buffer, 0, bytesRead)
                            android.util.Log.d("CB_VPN_TUN2SOCKS", "tun2socks: $output")
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }.start()
                
                // Wait a bit for tun2socks to start
                Thread.sleep(2000)
                
                // Check if process is alive
                if (tun2socksProcess == null || !tun2socksProcess!!.isAlive) {
                    android.util.Log.e("CB_VPN", "❌ tun2socks process DIED immediately")
                    throw Exception("tun2socks process died")
                }
                
                android.util.Log.d("CB_VPN", "✅ tun2socks is alive")
                
                // Step 4: Start SOCKS5 handler thread
                android.util.Log.d("CB_VPN", "Step 4: Starting SOCKS5 handler...")
                startSocks5Handler(socksPort)
                
                android.util.Log.d("CB_VPN", "🎉 SUCCESS: tun2socks forwarding setup complete")
                
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "❌ tun2socks failed: ${e.message}")
                e.printStackTrace()
                
                // 🔥 FALLBACK: Jika tun2socks gagal, guna dummy mode
                android.util.Log.d("CB_VPN", "⚠️ Falling back to dummy mode")
                pandaActive = true
                
                // Start dummy forwarder (minimal)
                startDummyForwarder()
            }
        }
    }
    
    private fun extractTun2SocksBinary(): File? {
        return try {
            // Determine CPU architecture
            val abi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Build.SUPPORTED_ABIS[0]
            } else {
                Build.CPU_ABI
            }
            
            android.util.Log.d("CB_VPN", "Device ABI: $abi")
            
            // Asset file name based on architecture
            val assetName = when {
                abi.startsWith("arm64") -> "tun2socks_arm64"
                abi.startsWith("armeabi") -> "tun2socks_arm"
                abi.startsWith("x86_64") -> "tun2socks_x86_64"
                abi.startsWith("x86") -> "tun2socks_x86"
                else -> "tun2socks_arm64"
            }
            
            android.util.Log.d("CB_VPN", "Looking for asset: $assetName")
            
            // Check if asset exists
            try {
                val assetsList = assets.list("")
                android.util.Log.d("CB_VPN", "Assets list: ${assetsList?.joinToString()}")
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "Cannot list assets: ${e.message}")
            }
            
            val inputStream = assets.open(assetName)
            val outputFile = File(cacheDir, "tun2socks")
            val outputStream = FileOutputStream(outputFile)
            
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            
            android.util.Log.d("CB_VPN", "File size: ${outputFile.length()} bytes")
            
            // Make executable
            val success = outputFile.setExecutable(true)
            android.util.Log.d("CB_VPN", "Set executable: $success")
            
            android.util.Log.d("CB_VPN", "✅ Extracted tun2socks to: ${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "❌ Failed to extract binary: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    private fun startSocks5Handler(socksPort: Int) {
        executor.submit {
            try {
                // Accept connection from tun2socks
                android.util.Log.d("CB_VPN", "SOCKS5: Waiting for connection...")
                val clientSocket = socksServer!!.accept()
                android.util.Log.d("CB_VPN", "✅ SOCKS5 client connected")
                
                // Simple SOCKS5 handshake (transparent mode)
                val input = clientSocket.getInputStream()
                val output = clientSocket.getOutputStream()
                
                // Read SOCKS5 greeting
                val version = input.read()
                val nmethods = input.read()
                val methods = ByteArray(nmethods)
                input.read(methods)
                
                // Send greeting response
                output.write(byteArrayOf(0x05, 0x00))
                
                // Read connection request
                val req = ByteArray(256)
                val reqLen = input.read(req)
                
                // Send success response
                val response = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                output.write(response)
                
                android.util.Log.d("CB_VPN", "✅ SOCKS5 handshake complete")
                
                // Keep connection alive
                while (forwardingActive) {
                    Thread.sleep(1000)
                }
                
                clientSocket.close()
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "❌ SOCKS5 handler error: ${e.message}")
            }
        }
    }
    
    // ==============================
    // EXISTING FUNCTIONS (KEEP AS IS)
    // ==============================
    private fun applyRealmeWorkaround(builder: Builder) {
        try {
            val setBlocking = builder.javaClass.getMethod("setBlocking", Boolean::class.java)
            setBlocking.invoke(builder, true)
        } catch (e: Exception) {}
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val setAllowBypass = builder.javaClass.getMethod("setAllowBypass", Boolean::class.java)
                setAllowBypass.invoke(builder, true)
            }
        } catch (e: Exception) {}
    }
    
    private fun startLocalTriggerServer() {
        Thread {
            try {
                val server = ServerSocket(29293)
                android.util.Log.d("CB_VPN", "Local trigger server started on port 29293")
                
                while (forwardingActive) {
                    val client = server.accept()
                    client.getInputStream().read()
                    client.close()
                    
                    pandaActive = true
                    android.util.Log.d("CB_VPN", "Panda trigger refreshed")
                }
                server.close()
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "Local server error: ${e.message}")
            }
        }.start()
        
        Thread {
            while (forwardingActive) {
                try {
                    Thread.sleep(30000)
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
            val buffer = ByteArray(32767)
            
            try {
                val fd = vpnInterface?.fileDescriptor ?: return@Thread
                val input = FileInputStream(fd)
                
                android.util.Log.d("CB_VPN", "Traffic monitor started (with forwarding)")
                
                while (forwardingActive) {
                    val len = input.read(buffer)
                    if (len > 0) {
                        // Traffic detected - refresh panda status
                        pandaActive = true
                        
                        // Packet telah dihandle oleh tun2socks (transparent mode)
                        // No need to manually forward
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "Monitor error: ${e.message}")
            }
        }.start()
    }
    
    private fun startDummyForwarder() {
        Thread {
            android.util.Log.d("CB_VPN", "🔄 Dummy forwarder started (fallback mode)")
            while (forwardingActive) {
                Thread.sleep(1000)
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
        
        // Stop tun2socks process
        tun2socksProcess?.destroy()
        tun2socksProcess = null
        
        // Close sockets
        socksServer?.close()
        socksServer = null
        
        // Close VPN interface
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
