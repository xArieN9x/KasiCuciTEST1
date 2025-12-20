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
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class AppMonitorVPNService : VpnService() {
    companion object {
        private var pandaActive = false
        private var lastPacketTime = 0L
        private var dnsIndex = 0
        private var instance: AppMonitorVPNService? = null

        fun isPandaActive(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastPacketTime > 3000) {
                pandaActive = false
            }
            return pandaActive
        }

        fun rotateDNS(dnsList: List<String>) {
            if (instance == null) return
            dnsIndex = (dnsIndex + 1) % dnsList.size
            val nextDNS = dnsList[dnsIndex]
            instance?.establishVPN(nextDNS)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwardingActive = false
    
    // ✅ ENHANCEMENT #1: Connection tracking with metadata
    private data class SocketInfo(
        val socket: Socket,
        val lastUsed: Long,
        val destIp: String,
        val destPort: Int
    )
    private val tcpConnections = ConcurrentHashMap<Int, SocketInfo>()
    
    // ✅ ENHANCEMENT #2: DNS cache
    private val dnsCache = ConcurrentHashMap<String, String>()
    
    private val workerPool = Executors.newCachedThreadPool()
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001
    
    // ✅ ENHANCEMENT #3: Connection limit
    private val MAX_CONNECTIONS = 8

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Panda Monitor running", connected = false))
        establishVPN("8.8.8.8")
        startConnectionCleanup()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Panda Monitor", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("Panda Monitor")
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    fun establishVPN(dns: String) {
        android.util.Log.i("CB_VPN_TRACE", "🚀 [VPN_SETUP] START - DNS: $dns")
        
        try {
            forwardingActive = false
            tcpConnections.values.forEach { it.socket.close() }
            tcpConnections.clear()
            vpnInterface?.close()
        } catch (_: Exception) {}
    
        val builder = Builder()
        builder.setSession("PandaMonitor")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAllowedApplication("com.logistics.rider.foodpanda")
            .addDnsServer(dns)
    
        vpnInterface = try {
            val iface = builder.establish()
            android.util.Log.i("CB_VPN_TRACE", "✅ [VPN_SETUP] SUCCESS - Interface: $iface")
            iface
        } catch (e: Exception) {
            android.util.Log.e("CB_VPN_TRACE", "❌ [VPN_SETUP] FAILED: ${e.message}")
            null
        }

        try {
            startForeground(NOTIF_ID, createNotification("Panda Monitor (DNS: $dns)", connected = vpnInterface != null))
        } catch (_: Exception) {}

        if (vpnInterface != null) {
            forwardingActive = true
            startPacketForwarding()
        }
    }

    private fun startPacketForwarding() {
        workerPool.execute {
            val buffer = ByteArray(2048)
            while (forwardingActive) {
                try {
                    val fd = vpnInterface?.fileDescriptor ?: break
                    val len = FileInputStream(fd).read(buffer)
                    if (len > 0) {
                        pandaActive = true
                        lastPacketTime = System.currentTimeMillis()
                        handleOutboundPacket(buffer.copyOfRange(0, len))
                    }
                } catch (e: Exception) {
                    pandaActive = false
                    Thread.sleep(100)
                }
            }
        }
    }

    // ✅ ENHANCEMENT #1: Connection cleanup thread
    private fun startConnectionCleanup() {
        workerPool.execute {
            android.util.Log.i("CB_VPN_TRACE", "🧹 [CLEANUP] Thread started")
            
            while (forwardingActive) {
                try {
                    Thread.sleep(10000) // Check every 10 seconds
                    
                    val totalConnections = tcpConnections.size
                    android.util.Log.d("CB_VPN_TRACE", "🔍 [CLEANUP] Checking $totalConnections connections")
                    
                    val now = System.currentTimeMillis()
                    val staleConnections = tcpConnections.filter { (port, info) ->
                        val idleTime = now - info.lastUsed
                        val isStale = idleTime > 60000 // 60 seconds idle
                        
                        if (isStale) {
                            android.util.Log.d("CB_VPN_TRACE", "⏰ [CLEANUP] Port $port idle for ${idleTime/1000}s (${info.destIp}:${info.destPort})")
                        }
                        isStale
                    }
                    
                    if (staleConnections.isNotEmpty()) {
                        android.util.Log.i("CB_VPN_TRACE", "🗑️ [CLEANUP] Removing ${staleConnections.size} stale connections")
                        
                        staleConnections.forEach { (port, info) ->
                            try {
                                android.util.Log.d("CB_VPN_TRACE", "🔌 [CLEANUP] Closing port $port (${info.destIp}:${info.destPort})")
                                info.socket.close()
                            } catch (closeException: Exception) {
                                android.util.Log.e("CB_VPN_TRACE", "❌ [CLEANUP] Error closing port $port: ${closeException.message}")
                            }
                            tcpConnections.remove(port)
                            android.util.Log.d("CB_VPN_TRACE", "✅ [CLEANUP] Port $port removed")
                        }
                        
                        android.util.Log.i("CB_VPN_TRACE", "📊 [CLEANUP] Remaining connections: ${tcpConnections.size}")
                    }
                    
                } catch (threadException: Exception) {
                    android.util.Log.e("CB_VPN_TRACE", "💥 [CLEANUP] Thread error: ${threadException.message}")
                }
            }
            
            android.util.Log.i("CB_VPN_TRACE", "🧹 [CLEANUP] Thread stopped")
        }
    }

    private fun handleOutboundPacket(packet: ByteArray) {
        try {
            // ✅ DEBUG: Log semua packet masuk
            android.util.Log.d("CB_VPN_TRACE", "📥 [PACKET_IN] Size: ${packet.size} bytes")
            
            val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
            if (ipHeaderLen < 20 || packet.size < ipHeaderLen + 20) return
            val protocol = packet[9].toInt() and 0xFF
            
            // ✅ DEBUG: Protocol type
            android.util.Log.d("CB_VPN_TRACE", "📊 [PROTOCOL] Type: $protocol (6=TCP, 17=UDP)")
            
            if (protocol != 6) return // Hanya TCP
    
            val destIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
            val srcPort = ((packet[ipHeaderLen].toInt() and 0xFF) shl 8) or (packet[ipHeaderLen + 1].toInt() and 0xFF)
            val destPort = ((packet[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (packet[ipHeaderLen + 3].toInt() and 0xFF)
            
            // ✅ DEBUG: TCP connection details
            android.util.Log.i("CB_VPN_TRACE", "🎯 [TCP_DETECTED] $destIp:$destPort (from port $srcPort)")

            // ✅ ENHANCEMENT #3: Limit max connections
            if (!tcpConnections.containsKey(srcPort) && tcpConnections.size >= MAX_CONNECTIONS) {
                // Remove oldest connection
                val oldest = tcpConnections.minByOrNull { it.value.lastUsed }
                oldest?.let {
                    try {
                        it.value.socket.close()
                    } catch (_: Exception) {}
                    tcpConnections.remove(it.key)
                }
            }

            if (!tcpConnections.containsKey(srcPort)) {
                workerPool.execute {
                    try {
                        android.util.Log.i("CB_VPN_TRACE", "🔗 [CONN_OUT] Creating socket to $destIp:$destPort")
                        val socket = Socket(destIp, destPort)
                        android.util.Log.i("CB_VPN_TRACE", "✅ [CONN_OUT] Socket created successfully")
                        
                        socket.tcpNoDelay = true
                        socket.soTimeout = 30000 // 30 second timeout
                        
                        // ✅ ENHANCEMENT #1: Store connection with metadata
                        val info = SocketInfo(
                            socket = socket,
                            lastUsed = System.currentTimeMillis(),
                            destIp = destIp,
                            destPort = destPort
                        )
                        tcpConnections[srcPort] = info

                        workerPool.execute {
                            val outStream = FileOutputStream(vpnInterface!!.fileDescriptor)
                            val inStream = socket.getInputStream()
                            val buf = ByteArray(2048)
                            try {
                                while (forwardingActive && socket.isConnected && !socket.isClosed) {
                                    val n = inStream.read(buf)
                                    if (n <= 0) break
                                    
                                    // ✅ Update last used time
                                    tcpConnections[srcPort]?.let {
                                        tcpConnections[srcPort] = it.copy(lastUsed = System.currentTimeMillis())
                                    }
                                    
                                    val reply = buildTcpPacket(destIp, destPort, "10.0.0.2", srcPort, buf.copyOfRange(0, n))
                                    outStream.write(reply)
                                    outStream.flush()
                                }
                            } catch (_: Exception) {}
                            tcpConnections.remove(srcPort)
                            socket.close()
                        }

                        if (payload.isNotEmpty()) {
                            socket.getOutputStream().write(payload)
                            socket.getOutputStream().flush()
                        }
                    } catch (_: Exception) {
                        android.util.Log.e("CB_VPN_TRACE", "❌ [CONN_OUT] Failed: ${e.message}")
                        tcpConnections.remove(srcPort)
                    }
                }
            } else {
                // ✅ ENHANCEMENT #1: Update last used & reuse connection
                tcpConnections[srcPort]?.let { info ->
                    try {
                        tcpConnections[srcPort] = info.copy(lastUsed = System.currentTimeMillis())
                        info.socket.getOutputStream()?.let {
                            it.write(payload)
                            it.flush()
                        }
                    } catch (_: Exception) {
                        android.util.Log.e("CB_VPN_TRACE", "❌ [CONN_OUT] Failed: ${e.message}")
                        tcpConnections.remove(srcPort)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun buildTcpPacket(srcIp: String, srcPort: Int, destIp: String, destPort: Int, payload: ByteArray): ByteArray {
    // ✅ DEBUG: Packet building
    android.util.Log.d("CB_VPN_TRACE", "🔨 [BUILD_REPLY] $destIp:$destPort -> $srcIp:$srcPort (${payload.size} bytes)")
    
        val totalLen = 40 + payload.size
        val packet = ByteArray(totalLen)
        packet[0] = 0x45
        packet[2] = (totalLen ushr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[8] = 0xFF.toByte()
        packet[9] = 0x06
        val src = srcIp.split(".")
        packet[12] = src[0].toUByte().toByte()
        packet[13] = src[1].toUByte().toByte()
        packet[14] = src[2].toUByte().toByte()
        packet[15] = src[3].toUByte().toByte()
        val dest = destIp.split(".")
        packet[16] = dest[0].toUByte().toByte()
        packet[17] = dest[1].toUByte().toByte()
        packet[18] = dest[2].toUByte().toByte()
        packet[19] = dest[3].toUByte().toByte()
        packet[20] = (srcPort ushr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (destPort ushr 8).toByte()
        packet[23] = (destPort and 0xFF).toByte()
        packet[32] = 0x50
        packet[33] = if (payload.isEmpty()) 0x02 else 0x18.toByte()
        packet[34] = 0xFF.toByte()
        packet[35] = 0xFF.toByte()
        System.arraycopy(payload, 0, packet, 40, payload.size)

        android.util.Log.d("CB_VPN_TRACE", "📤 [REPLY_SENT] ${totalLen} bytes")
        return packet
    }

    override fun onDestroy() {
        forwardingActive = false
        tcpConnections.values.forEach { it.socket.close() }
        tcpConnections.clear()
        dnsCache.clear()
        workerPool.shutdownNow()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        pandaActive = false
        lastPacketTime = 0L
        instance = null
        super.onDestroy()
    }
}
