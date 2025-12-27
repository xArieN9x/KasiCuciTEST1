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
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

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
    
    // ✅ FIX: Track TCP connection states
    data class TcpConnectionState(
        var serverSeq: Long = 1000L,  // Initial sequence number
        var clientSeq: Long = 0L,
        var lastAckSent: Long = 0L
    )
    private val tcpConnections = ConcurrentHashMap<String, TcpConnectionState>()
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("CB Starting...", false))
    
        val builder = Builder()
        builder.setSession("CB Monitor")
        .setMtu(1500)
        .addAddress("10.222.222.2", 30)
        .addRoute("0.0.0.0", 0)
        .addDnsServer("8.8.8.8")
        .addDnsServer("1.1.1.1")
        .allowFamily(2)
        
        try {
            builder.addAllowedApplication("com.logistics.rider.foodpanda")
        } catch (e: Exception) {}
        try {
            builder.addAllowedApplication("com.android.chrome")
        } catch (e: Exception) {}
        applyRealmeWorkaround(builder)
    
        vpnInterface = try { builder.establish() } catch (e: Exception) {
            android.util.Log.e("CB_VPN", "VPN failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
    
        if (vpnInterface != null) {
            forwardingActive = true
            startForwarder()
            pandaActive = true
            
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, createNotification("CB Active ✅", true))
            android.util.Log.d("CB_VPN", "✅ VPN STARTED")
        } else { stopSelf() }
    
        return START_STICKY
    }
    
    private fun startForwarder() {
        Thread {
            val pkt = ByteArray(32767)
            try {
                val fd = vpnInterface?.fileDescriptor ?: return@Thread
                val input = FileInputStream(fd)
                val output = FileOutputStream(fd)
                
                android.util.Log.d("CB_VPN", "Forwarder STARTED")
                
                while (forwardingActive) {
                    val len = input.read(pkt)
                    if (len > 0) {
                        pandaActive = true
                        if (len < 20) continue
                        
                        val ver = (pkt[0].toInt() shr 4) and 0xF
                        if (ver != 4) continue
                        
                        val proto = pkt[9].toInt() and 0xFF
                        val dstIp = ip(pkt, 16)
                        
                        when (proto) {
                            17 -> {
                                if (len >= 28) {
                                    val dPort = port(pkt, 22)
                                    forwardUDP(pkt, len, dstIp, dPort, output)
                                }
                            }
                            6 -> {
                                if (len >= 40) {
                                    val dPort = port(pkt, 22)
                                    forwardTCP(pkt, len, dstIp, dPort)
                                }
                            }
                            1 -> output.write(pkt, 0, len)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "Forwarder error: ${e.message}")
            }
        }.start()
        
        Thread {
            while (forwardingActive) {
                try { Thread.sleep(30000); android.util.Log.d("CB_VPN", "💚 Active") } catch (e: Exception) { break }
            }
        }.start()
    }
    
    private fun forwardUDP(pkt: ByteArray, len: Int, dIp: String, dPort: Int, out: FileOutputStream) {
        Thread {
            try {
                val sock = DatagramSocket()
                protect(sock)
                
                val pLen = len - 28
                if (pLen <= 0) return@Thread
                
                val payload = pkt.copyOfRange(28, 28 + pLen)
                sock.send(DatagramPacket(payload, pLen, InetAddress.getByName(dIp), dPort))
                
                sock.soTimeout = 200
                val recv = ByteArray(32767)
                val recvPkt = DatagramPacket(recv, recv.size)
                
                try {
                    sock.receive(recvPkt)
                    val resp = buildUDPResp(recvPkt.data, recvPkt.length, dIp, dPort,
                        pkt[12], pkt[13], pkt[14], pkt[15], port(pkt, 20))
                    out.write(resp)
                    android.util.Log.d("CB_VPN", "✅ UDP: ${recvPkt.length}b")
                } catch (e: Exception) {}
                
                sock.close()
            } catch (e: Exception) {}
        }.start()
    }
    
    private fun forwardTCP(pkt: ByteArray, len: Int, dIp: String, dPort: Int) {
        Thread {
            var channel: SocketChannel? = null
            try {
                val fd = vpnInterface?.fileDescriptor ?: return@Thread
                val tunOut = FileOutputStream(fd)
    
                val ipHdrLen = ((pkt[0].toInt() and 0x0F) * 4)
                val srcIp = ip(pkt, 12)
                val srcPort = port(pkt, ipHdrLen)
                
                val tcpFlags = if (len > ipHdrLen + 13) pkt[ipHdrLen + 13].toInt() and 0xFF else 0
                val isSyn = (tcpFlags and 0x02) != 0
                val isAck = (tcpFlags and 0x10) != 0
                val isRst = (tcpFlags and 0x04) != 0
                val isFin = (tcpFlags and 0x01) != 0
                
                // ✅ Connection key for state tracking
                val connKey = "$srcIp:$srcPort-$dIp:$dPort"
                
                if (isSyn && !isAck) {
                    android.util.Log.d("CB_VPN", "🔄 TCP HANDSHAKE: SYN from $srcIp:$srcPort to $dIp:$dPort")
                    
                    val clientSeqNum = ByteArray(4)
                    if (len >= ipHdrLen + 8) {
                        System.arraycopy(pkt, ipHdrLen + 4, clientSeqNum, 0, 4)
                    }
                    
                    // ✅ FIX: Store initial state
                    val clientSeqLong = bytesToLong(clientSeqNum)
                    tcpConnections[connKey] = TcpConnectionState(
                        serverSeq = 1000L,
                        clientSeq = clientSeqLong,
                        lastAckSent = clientSeqLong + 1
                    )
                    
                    val synAckResponse = buildSynAckResponse(
                        dIp, dPort,
                        srcIp, srcPort,
                        clientSeqNum
                    )
                    
                    tunOut.write(synAckResponse)
                    android.util.Log.d("CB_VPN", "✅ Sent SYN-ACK to client")
                    return@Thread
                }

                if (isAck && !isSyn && !isFin && !isRst) {
                    android.util.Log.d("CB_VPN", "✅ Client ACK received: $srcIp:$srcPort → $dIp:$dPort")
                }
                
                if (isRst || isFin) {
                    android.util.Log.d("CB_VPN", "TCP $dIp:$dPort RST/FIN - closing")
                    tcpConnections.remove(connKey)  // ✅ Cleanup
                    return@Thread
                }
                
                channel = SocketChannel.open()
                channel.configureBlocking(true)
                channel.socket().soTimeout = 10000
                channel.socket().tcpNoDelay = true
    
                if (!protect(channel.socket())) {
                    android.util.Log.e("CB_VPN", "❌ Protect failed")
                    channel.close()
                    return@Thread
                }
    
                channel.connect(InetSocketAddress(dIp, dPort))
                android.util.Log.d("CB_VPN", "✅ Connected to $dIp:$dPort")
    
                val tcpHdrLen = if (len > ipHdrLen + 12) 
                    ((pkt[ipHdrLen + 12].toInt() and 0xF0) shr 4) * 4 
                else 20
                
                val payloadOffset = ipHdrLen + tcpHdrLen
                val payloadLen = len - payloadOffset
                
                if (payloadLen > 0) {
                    val payload = ByteBuffer.wrap(pkt, payloadOffset, payloadLen)
                    val written = channel.write(payload)
                    android.util.Log.d("CB_VPN", "Sent $written bytes to $dIp:$dPort")
                }
    
                val readBuffer = ByteBuffer.allocate(65535)
                val readLen = channel.read(readBuffer)
                
                if (readLen > 0) {
                    readBuffer.flip()
                    val responseData = ByteArray(readLen)
                    readBuffer.get(responseData)
                    
                    // ✅ FIX: Get or create connection state
                    val state = tcpConnections.getOrPut(connKey) {
                        TcpConnectionState(
                            serverSeq = 1000L,
                            clientSeq = 0L,
                            lastAckSent = 0L
                        )
                    }
                    
                    // ✅ FIX: Extract client sequence from incoming packet
                    val clientSeqBytes = ByteArray(4)
                    if (len >= ipHdrLen + 8) {
                        System.arraycopy(pkt, ipHdrLen + 4, clientSeqBytes, 0, 4)
                    }
                    val clientSeqLong = bytesToLong(clientSeqBytes)
                    
                    // ✅ FIX: Calculate correct ACK (client seq + client payload length)
                    val ackNumLong = clientSeqLong + payloadLen
                    state.lastAckSent = ackNumLong
                    
                    // ✅ FIX: Build response with tracked sequence numbers
                    val tcpResponse = buildTCPDataResponse(
                        responseData, readLen,
                        dIp, dPort,          // Server (source of response)
                        srcIp, srcPort,      // Client (destination)
                        longToBytes(state.serverSeq),  // Server sequence
                        longToBytes(ackNumLong),       // ACK = client seq + payload
                        true
                    )
                    
                    tunOut.write(tcpResponse)
                    
                    // ✅ FIX: Increment server sequence by data sent
                    state.serverSeq += readLen
                    
                    android.util.Log.d("CB_VPN", "✅ TCP Data Response: $readLen bytes (seq=${state.serverSeq - readLen}, ack=$ackNumLong)")
                }
    
                channel.close()
    
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "TCP error $dIp:$dPort: ${e.message}")
            } finally {
                try { channel?.close() } catch (_: Exception) {}
            }
        }.start()
    }

    // ✅ NEW: Helper functions for sequence number conversion
    private fun bytesToLong(bytes: ByteArray): Long {
        var result = 0L
        for (i in 0..3) {
            result = (result shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return result and 0xFFFFFFFFL  // Keep as unsigned 32-bit
    }
    
    private fun longToBytes(value: Long): ByteArray {
        val bytes = ByteArray(4)
        bytes[0] = ((value shr 24) and 0xFF).toByte()
        bytes[1] = ((value shr 16) and 0xFF).toByte()
        bytes[2] = ((value shr 8) and 0xFF).toByte()
        bytes[3] = (value and 0xFF).toByte()
        return bytes
    }

    private fun buildSynAckResponse(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        clientSeqNum: ByteArray
    ): ByteArray {
        val ipHdrLen = 20
        val tcpHdrLen = 20
        val totalLen = ipHdrLen + tcpHdrLen
        val resp = ByteArray(totalLen)
    
        resp[0] = 0x45.toByte()
        resp[1] = 0x00.toByte()
        resp[2] = (totalLen shr 8).toByte()
        resp[3] = (totalLen and 0xFF).toByte()
        resp[4] = 0x00.toByte()
        resp[5] = 0x00.toByte()
        resp[6] = 0x40.toByte()
        resp[7] = 0x00.toByte()
        resp[8] = 64.toByte()
        resp[9] = 6.toByte()
    
        val srcParts = srcIp.split(".")
        for (i in 0..3) {
            resp[12 + i] = srcParts[i].toInt().toByte()
        }
    
        val dstParts = dstIp.split(".")
        for (i in 0..3) {
            resp[16 + i] = dstParts[i].toInt().toByte()
        }
    
        resp[10] = 0x00
        resp[11] = 0x00
        val ipCsum = csum(resp, 0, ipHdrLen)
        resp[10] = (ipCsum shr 8).toByte()
        resp[11] = (ipCsum and 0xFF).toByte()
    
        val tcpOffset = ipHdrLen
    
        wPort(resp, tcpOffset, srcPort)
        wPort(resp, tcpOffset + 2, dstPort)
    
        resp[tcpOffset + 4] = 0x00.toByte()
        resp[tcpOffset + 5] = 0x00.toByte()
        resp[tcpOffset + 6] = 0x03.toByte()
        resp[tcpOffset + 7] = 0xE8.toByte()  // Seq = 1000
    
        val ackNum = ByteArray(4)
        System.arraycopy(clientSeqNum, 0, ackNum, 0, 4)
        var carry = 1
        for (i in 3 downTo 0) {
            val current = (ackNum[i].toInt() and 0xFF) + carry
            ackNum[i] = (current and 0xFF).toByte()
            carry = current shr 8
            if (carry == 0) break
        }
        System.arraycopy(ackNum, 0, resp, tcpOffset + 8, 4)
    
        resp[tcpOffset + 12] = 0x50.toByte()
        resp[tcpOffset + 13] = 0x12.toByte()
        resp[tcpOffset + 14] = 0xFF.toByte()
        resp[tcpOffset + 15] = 0xFF.toByte()
    
        resp[tcpOffset + 16] = 0x00
        resp[tcpOffset + 17] = 0x00
        resp[tcpOffset + 18] = 0x00
        resp[tcpOffset + 19] = 0x00
    
        val tcpCsum = calculateTcpChecksum(resp, tcpOffset, tcpHdrLen, srcIp, dstIp)
        resp[tcpOffset + 16] = (tcpCsum shr 8).toByte()
        resp[tcpOffset + 17] = (tcpCsum and 0xFF).toByte()
    
        return resp
    }
        
    private fun buildTCPDataResponse(
        payload: ByteArray, pLen: Int,
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        serverSeqNum: ByteArray,  // ✅ FIX: Use tracked sequence
        ackNum: ByteArray,
        hasAckFlag: Boolean
    ): ByteArray {
        return buildTCPResponse(payload, pLen, srcIp, srcPort, dstIp, dstPort, 
                              serverSeqNum, ackNum, hasAckFlag)
    }

    private fun buildUDPResp(pl: ByteArray, pLen: Int, sIp: String, sPort: Int,
                            d0: Byte, d1: Byte, d2: Byte, d3: Byte, dPort: Int): ByteArray {
        val tot = 28 + pLen
        val r = ByteArray(tot)
        
        r[0] = 0x45.toByte(); r[2] = (tot shr 8).toByte(); r[3] = (tot and 0xFF).toByte()
        r[8] = 64.toByte(); r[9] = 17.toByte()
        
        val s = sIp.split(".")
        r[12] = s[0].toInt().toByte(); r[13] = s[1].toInt().toByte()
        r[14] = s[2].toInt().toByte(); r[15] = s[3].toInt().toByte()
        
        r[16] = d0; r[17] = d1; r[18] = d2; r[19] = d3
        
        wPort(r, 20, sPort); wPort(r, 22, dPort)
        
        val uLen = 8 + pLen
        r[24] = (uLen shr 8).toByte(); r[25] = (uLen and 0xFF).toByte()
        
        System.arraycopy(pl, 0, r, 28, pLen)
        
        val c = csum(r, 0, 20)
        r[10] = (c shr 8).toByte(); r[11] = (c and 0xFF).toByte()
        
        return r
    }
    
    private fun ip(p: ByteArray, o: Int) = "${p[o].toInt() and 0xFF}.${p[o+1].toInt() and 0xFF}.${p[o+2].toInt() and 0xFF}.${p[o+3].toInt() and 0xFF}"
    private fun port(p: ByteArray, o: Int) = ((p[o].toInt() and 0xFF) shl 8) or (p[o+1].toInt() and 0xFF)
    private fun wPort(p: ByteArray, o: Int, pt: Int) { p[o] = (pt shr 8).toByte(); p[o+1] = (pt and 0xFF).toByte() }
    private fun csum(d: ByteArray, s: Int, l: Int): Int {
        var sum = 0L; var i = s
        while (i < s + l) {
            sum += ((d[i].toInt() and 0xFF) shl 8) or (if (i+1 < s+l) d[i+1].toInt() and 0xFF else 0); i += 2
        }
        while (sum shr 16 > 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv().toInt() and 0xFFFF)
    }

    private fun buildTCPResponse(
        payload: ByteArray, pLen: Int,
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        serverSeqNum: ByteArray,  // ✅ FIX: Use passed sequence
        ackNum: ByteArray,
        hasAckFlag: Boolean
    ): ByteArray {
        val ipHdrLen = 20
        val tcpHdrLen = 20
        val totalLen = ipHdrLen + tcpHdrLen + pLen
        
        val resp = ByteArray(totalLen)
        
        resp[0] = 0x45.toByte()
        resp[1] = 0x00.toByte()
        resp[2] = (totalLen shr 8).toByte()
        resp[3] = (totalLen and 0xFF).toByte()
        resp[4] = 0x00.toByte()
        resp[5] = 0x00.toByte()
        resp[6] = 0x40.toByte()
        resp[7] = 0x00.toByte()
        resp[8] = 64.toByte()
        resp[9] = 6.toByte()
        
        resp[10] = 0x00
        resp[11] = 0x00
        
        val src = srcIp.split(".")
        for (i in 0..3) {
            resp[12 + i] = src[i].toInt().toByte()
        }
        
        val dst = dstIp.split(".")
        for (i in 0..3) {
            resp[16 + i] = dst[i].toInt().toByte()
        }
        
        val tcpOffset = ipHdrLen
        
        wPort(resp, tcpOffset, srcPort)
        wPort(resp, tcpOffset + 2, dstPort)
        
        // ✅ FIX: Use tracked server sequence number
        System.arraycopy(serverSeqNum, 0, resp, tcpOffset + 4, 4)
        
        System.arraycopy(ackNum, 0, resp, tcpOffset + 8, 4)
        
        resp[tcpOffset + 12] = 0x50.toByte()
        
        val flags = if (hasAckFlag) 0x18.toByte() else 0x10.toByte()
        resp[tcpOffset + 13] = flags
        
        resp[tcpOffset + 14] = 0xFF.toByte()
        resp[tcpOffset + 15] = 0xFF.toByte()
        
        resp[tcpOffset + 16] = 0x00
        resp[tcpOffset + 17] = 0x00
        resp[tcpOffset + 18] = 0x00
        resp[tcpOffset + 19] = 0x00
        
        System.arraycopy(payload, 0, resp, tcpOffset + tcpHdrLen, pLen)
        
        val ipCsum = csum(resp, 0, ipHdrLen)
        resp[10] = (ipCsum shr 8).toByte()
        resp[11] = (ipCsum and 0xFF).toByte()
        
        val tcpCsum = calculateTcpChecksum(resp, ipHdrLen, tcpHdrLen + pLen, srcIp, dstIp)
        resp[tcpOffset + 16] = (tcpCsum shr 8).toByte()
        resp[tcpOffset + 17] = (tcpCsum and 0xFF).toByte()
        
        return resp
    }
    
    private fun calculateTcpChecksum(
        packet: ByteArray,
        tcpStart: Int,
        tcpLength: Int,
        srcIp: String,
        dstIp: String
    ): Int {
        val pseudo = ByteArray(12 + tcpLength)
    
        val src = srcIp.split(".")
        for (i in 0..3) pseudo[i] = src[i].toInt().toByte()
    
        val dst = dstIp.split(".")
        for (i in 0..3) pseudo[4 + i] = dst[i].toInt().toByte()
    
        pseudo[8] = 0x00
        pseudo[9] = 0x06.toByte()
    
        pseudo[10] = (tcpLength shr 8).toByte()
        pseudo[11] = (tcpLength and 0xFF).toByte()
    
        System.arraycopy(packet, tcpStart, pseudo, 12, tcpLength)
    
        pseudo[12 + 16] = 0x00
        pseudo[12 + 17] = 0x00
    
        return csum(pseudo, 0, pseudo.size)
    }
    
    private fun applyRealmeWorkaround(builder: Builder) {
        try { builder.javaClass.getMethod("setBlocking", Boolean::class.java).invoke(builder, false) } catch (e: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.javaClass.getMethod("setAllowBypass", Boolean::class.java).invoke(builder, true)
            }
        } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "CB Monitor", NotificationManager.IMPORTANCE_LOW)
            nm?.createNotificationChannel(ch)
        }
    }

    private fun createNotification(txt: String, con: Boolean): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CB Panda Monitor")
            .setContentText(txt)
            .setSmallIcon(if (con) android.R.drawable.presence_online else android.R.drawable.presence_busy)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun cleanup() {
        forwardingActive = false
        vpnInterface?.close()
        pandaActive = false
        instance = null
        tcpConnections.clear()  // ✅ Cleanup state
        stopForeground(true)
    }

    override fun onDestroy() { cleanup(); super.onDestroy() }
}
