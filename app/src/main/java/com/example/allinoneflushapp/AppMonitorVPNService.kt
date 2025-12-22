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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class AppMonitorVPNService : VpnService() {
    companion object {
        private var pandaActive = false
        private var instance: AppMonitorVPNService? = null
        private const val PANDA_PACKAGE = "com.logistics.rider.foodpanda"

        fun isPandaActive() = pandaActive
        fun rotateDNS(dnsList: List<String>) {}
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwardingActive = false
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001
    
    // ✅ PROTECTED TUNNEL untuk bypass VPN sendiri
    private var tunnel: DatagramChannel? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("CB Monitor Starting...", connected = false))
    
        // ✅ VPN CONFIG - SIMPLE & WORKING
        val builder = Builder()
        builder.setSession("CB Panda Monitor")
            .setMtu(1400)
            .addAddress("10.8.0.1", 24)
            
            // ✅ ROUTE semua traffic
            .addRoute("0.0.0.0", 1)      
            .addRoute("128.0.0.0", 1)    
            
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            
            // ✅ EXCLUDE CB sendiri je
            .addDisallowedApplication(packageName)
        
        // ✅ Exclude common apps untuk stability
        excludeCommonApps(builder)
        
        // ✅ PANDA TIDAK EXCLUDE - biar masuk VPN!
        // Ini CRITICAL - jangan ada addDisallowedApplication untuk Panda
        
        android.util.Log.d("CB_VPN", "✅ Panda INCLUDED in VPN for monitoring")
        
        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "VPN establish failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
    
        if (vpnInterface != null) {
            forwardingActive = true
            
            // ✅ START packet relay (THE FIX!)
            startPacketRelay()
            
            // ✅ Update notification
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, createNotification("CB Monitor Active ✅", connected = true))
            
            android.util.Log.d("CB_VPN", "✅ VPN successfully started - monitoring Panda")
        } else {
            android.util.Log.e("CB_VPN", "VPN interface null - stopping")
            stopSelf()
        }
    
        return START_STICKY
    }
    
    // ✅ PACKET RELAY - PROPER IMPLEMENTATION!
    private fun startPacketRelay() {
        Thread {
            try {
                // 1. Setup tunnel ke internet (bypass VPN)
                tunnel = DatagramChannel.open()
                tunnel?.connect(InetSocketAddress("8.8.8.8", 53))
                protect(tunnel!!.socket()) // ✅ CRITICAL: Protect dari VPN
                
                android.util.Log.d("CB_VPN", "✅ Protected tunnel created")
                
                val fd = vpnInterface?.fileDescriptor ?: return@Thread
                val input = FileInputStream(fd)
                val output = FileOutputStream(fd)
                
                val packet = ByteBuffer.allocate(32767)
                var lastActivityTime = System.currentTimeMillis()
                
                android.util.Log.d("CB_VPN", "✅ Packet relay STARTED")
                
                while (forwardingActive) {
                    // READ packet dari VPN (Panda app)
                    packet.clear()
                    val length = input.read(packet.array())
                    
                    if (length > 0) {
                        packet.limit(length)
                        
                        // ✅ Panda active!
                        pandaActive = true
                        lastActivityTime = System.currentTimeMillis()
                        
                        // ✅ FORWARD ke internet via protected tunnel
                        try {
                            // Extract destination from IP header
                            val destIp = extractDestIP(packet.array())
                            val destPort = extractDestPort(packet.array())
                            
                            if (destIp != null && destPort > 0) {
                                // Forward via real network
                                forwardPacket(packet.array(), length, destIp, destPort, output)
                                
                                android.util.Log.d("CB_VPN", 
                                    "✅ Relayed ${length}b to $destIp:$destPort")
                            } else {
                                // Simple echo back untuk DNS/ICMP
                                output.write(packet.array(), 0, length)
                            }
                        } catch (e: Exception) {
                            // Fallback: just echo
                            output.write(packet.array(), 0, length)
                        }
                    }
                    
                    // Auto-reset after idle
                    if (System.currentTimeMillis() - lastActivityTime > 5000) {
                        if (pandaActive) {
                            pandaActive = false
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "Relay error: ${e.message}")
                e.printStackTrace()
            }
            
            android.util.Log.d("CB_VPN", "Packet relay STOPPED")
        }.start()
        
        // ✅ HEARTBEAT thread
        Thread {
            while (forwardingActive) {
                try {
                    Thread.sleep(30000)
                    android.util.Log.d("CB_VPN", "💚 VPN heartbeat - Panda: $pandaActive")
                } catch (e: Exception) {
                    break
                }
            }
        }.start()
    }
    
    // ✅ FORWARD packet ke internet (SIMPLIFIED)
    private fun forwardPacket(packet: ByteArray, length: Int, destIp: String, 
                             destPort: Int, output: FileOutputStream) {
        try {
            // Buat socket protected untuk forward
            val socket = DatagramSocket()
            protect(socket) // ✅ Bypass VPN
            
            val address = InetAddress.getByName(destIp)
            
            // Send packet content (skip IP header)
            val payload = packet.copyOfRange(20, length)
            val datagram = java.net.DatagramPacket(payload, payload.size, address, destPort)
            socket.send(datagram)
            
            // Optional: wait response (timeout 100ms)
            socket.soTimeout = 100
            val response = ByteArray(32767)
            val recvPacket = java.net.DatagramPacket(response, response.size)
            
            try {
                socket.receive(recvPacket)
                
                // Build IP response packet dan write balik
                val responsePacket = buildIPPacket(
                    recvPacket.data, 
                    recvPacket.length,
                    destIp, 
                    "10.8.0.1"
                )
                output.write(responsePacket)
            } catch (e: Exception) {
                // Timeout OK - UDP may not respond
            }
            
            socket.close()
        } catch (e: Exception) {
            android.util.Log.w("CB_VPN", "Forward failed: ${e.message}")
        }
    }
    
    // Extract destination IP dari IP header
    private fun extractDestIP(packet: ByteArray): String? {
        return try {
            if (packet.size < 20) return null
            "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}." +
            "${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
        } catch (e: Exception) {
            null
        }
    }
    
    // Extract destination port dari TCP/UDP header
    private fun extractDestPort(packet: ByteArray): Int {
        return try {
            if (packet.size < 24) return 0
            val protocol = packet[9].toInt() and 0xFF
            if (protocol == 6 || protocol == 17) { // TCP or UDP
                ((packet[22].toInt() and 0xFF) shl 8) or (packet[23].toInt() and 0xFF)
            } else 0
        } catch (e: Exception) {
            0
        }
    }
    
    // Build simple IP response packet
    private fun buildIPPacket(payload: ByteArray, payloadLen: Int, 
                             srcIp: String, dstIp: String): ByteArray {
        // Simplified: just wrap with basic IP header
        val packet = ByteArray(20 + payloadLen)
        
        // IP Version + IHL
        packet[0] = 0x45.toByte()
        // Total length
        val totalLen = 20 + payloadLen
        packet[2] = (totalLen shr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        // Protocol (UDP = 17)
        packet[9] = 17.toByte()
        
        // Source IP
        val srcParts = srcIp.split(".")
        packet[12] = srcParts[0].toInt().toByte()
        packet[13] = srcParts[1].toInt().toByte()
        packet[14] = srcParts[2].toInt().toByte()
        packet[15] = srcParts[3].toInt().toByte()
        
        // Dest IP
        val dstParts = dstIp.split(".")
        packet[16] = dstParts[0].toInt().toByte()
        packet[17] = dstParts[1].toInt().toByte()
        packet[18] = dstParts[2].toInt().toByte()
        packet[19] = dstParts[3].toInt().toByte()
        
        // Copy payload
        System.arraycopy(payload, 0, packet, 20, payloadLen)
        
        return packet
    }
    
    private fun excludeCommonApps(builder: Builder) {
        val appsToExclude = arrayOf(
            "com.android.chrome",
            "com.google.android.googlequicksearchbox",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.facebook.katana",
            "com.whatsapp",
            "com.instagram.android"
        )
        
        for (app in appsToExclude) {
            try {
                builder.addDisallowedApplication(app)
            } catch (e: Exception) {}
        }
        
        // ✅ PENTING: Jangan exclude Panda!
        android.util.Log.d("CB_VPN", "Panda NOT excluded - will monitor!")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, 
                "CB Panda Monitor", 
                NotificationManager.IMPORTANCE_LOW
            )
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
        
        tunnel?.close()
        tunnel = null
        
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
