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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

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
        startForeground(NOTIF_ID, createNotification("CB Monitor Starting...", false))
    
        val builder = Builder()
        builder.setSession("CB Monitor")
            .setMtu(1500)
            .addAddress("10.215.173.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
        
        // ✅ EXCLUDE Chrome sahaja (Panda MASUK VPN)
        try {
            builder.addDisallowedApplication("com.android.chrome")
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
            startPacketForwarder() // ✅ NO tun2socks!
            pandaActive = true
            
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, createNotification("CB Monitor Active ✅", true))
            android.util.Log.d("CB_VPN", "✅ VPN STARTED - Direct forwarding")
        } else {
            stopSelf()
        }
    
        return START_STICKY
    }
    
    // ✅ BACA TUN + FORWARD (PCAPdroid style)
    private fun startPacketForwarder() {
        Thread {
            val packet = ByteArray(32767)
            
            try {
                val fd = vpnInterface?.fileDescriptor ?: return@Thread
                val input = FileInputStream(fd)
                val output = FileOutputStream(fd)
                
                android.util.Log.d("CB_VPN", "Packet forwarder STARTED")
                
                while (forwardingActive) {
                    val len = input.read(packet)
                    
                    if (len > 0) {
                        pandaActive = true
                        
                        // Parse IP header
                        if (len < 20) continue
                        
                        val version = (packet[0].toInt() shr 4) and 0xF
                        if (version != 4) continue
                        
                        val protocol = packet[9].toInt() and 0xFF
                        val srcIp = ipToString(packet, 12)
                        val dstIp = ipToString(packet, 16)
                        
                        when (protocol) {
                            17 -> { // UDP
                                if (len >= 28) {
                                    val dstPort = readPort(packet, 22)
                                    android.util.Log.d("CB_VPN", "UDP: $srcIp → $dstIp:$dstPort (${len}b)")
                                    forwardUDP(packet, len, dstIp, dstPort, output)
                                }
                            }
                            6 -> { // TCP
                                android.util.Log.d("CB_VPN", "TCP: $srcIp → $dstIp (${len}b)")
                                // Echo back (simple forward)
                                output.write(packet, 0, len)
                            }
                            1 -> { // ICMP
                                android.util.Log.d("CB_VPN", "ICMP: $srcIp → $dstIp")
                                output.write(packet, 0, len)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CB_VPN", "Forwarder error: ${e.message}")
            }
            
            android.util.Log.d("CB_VPN", "Packet forwarder STOPPED")
        }.start()
        
        // Heartbeat
        Thread {
            while (forwardingActive) {
                try {
                    Thread.sleep(30000)
                    android.util.Log.d("CB_VPN", "💚 Active: $pandaActive")
                } catch (e: Exception) {
                    break
                }
            }
        }.start()
    }
    
    private fun forwardUDP(pkt: ByteArray, len: Int, dstIp: String, dstPort: Int, out: FileOutputStream) {
        Thread {
            try {
                val socket = DatagramSocket()
                protect(socket) // ✅ CRITICAL!
                
                val payloadLen = len - 28
                if (payloadLen <= 0) return@Thread
                
                val payload = pkt.copyOfRange(28, 28 + payloadLen)
                val addr = InetAddress.getByName(dstIp)
                
                socket.send(DatagramPacket(payload, payloadLen, addr, dstPort))
                
                socket.soTimeout = 200
                val recvBuf = ByteArray(32767)
                val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                
                try {
                    socket.receive(recvPkt)
                    val response = buildUDPResponse(recvPkt.data, recvPkt.length, dstIp, dstPort,
                        pkt[12], pkt[13], pkt[14], pkt[15], readPort(pkt, 20))
                    out.write(response)
                    android.util.Log.d("CB_VPN", "✅ UDP replied: ${recvPkt.length}b")
                } catch (e: Exception) {
                    // Timeout OK
                }
                
                socket.close()
            } catch (e: Exception) {
                android.util.Log.w("CB_VPN", "UDP forward failed: ${e.message}")
            }
        }.start()
    }
    
    private fun buildUDPResponse(payload: ByteArray, pLen: Int, srcIp: String, srcPort: Int,
                                 d0: Byte, d1: Byte, d2: Byte, d3: Byte, dPort: Int): ByteArray {
        val total = 28 + pLen
        val resp = ByteArray(total)
        
        resp[0] = 0x45.toByte()
        resp[2] = (total shr 8).toByte()
        resp[3] = (total and 0xFF).toByte()
        resp[8] = 64.toByte()
        resp[9] = 17.toByte()
        
        val src = srcIp.split(".")
        resp[12] = src[0].toInt().toByte()
        resp[13] = src[1].toInt().toByte()
        resp[14] = src[2].toInt().toByte()
        resp[15] = src[3].toInt().toByte()
        
        resp[16] = d0
        resp[17] = d1
        resp[18] = d2
        resp[19] = d3
        
        writePort(resp, 20, srcPort)
        writePort(resp, 22, dPort)
        
        val udpLen = 8 + pLen
        resp[24] = (udpLen shr 8).toByte()
        resp[25] = (udpLen and 0xFF).toByte()
        
        System.arraycopy(payload, 0, resp, 28, pLen)
        
        val ipCsum = checksum(resp, 0, 20)
        resp[10] = (ipCsum shr 8).toByte()
        resp[11] = (ipCsum and 0xFF).toByte()
        
        return resp
    }
    
    private fun ipToString(pkt: ByteArray, off: Int): String =
        "${pkt[off].toInt() and 0xFF}.${pkt[off+1].toInt() and 0xFF}." +
        "${pkt[off+2].toInt() and 0xFF}.${pkt[off+3].toInt() and 0xFF}"
    
    private fun readPort(pkt: ByteArray, off: Int): Int =
        ((pkt[off].toInt() and 0xFF) shl 8) or (pkt[off+1].toInt() and 0xFF)
    
    private fun writePort(pkt: ByteArray, off: Int, port: Int) {
        pkt[off] = (port shr 8).toByte()
        pkt[off+1] = (port and 0xFF).toByte()
    }
    
    private fun checksum(data: ByteArray, start: Int, len: Int): Int {
        var sum = 0L
        var i = start
        while (i < start + len) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or 
                   (if (i+1 < start+len) data[i+1].toInt() and 0xFF else 0)
            i += 2
        }
        while (sum shr 16 > 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv().toInt() and 0xFFFF)
    }
    
    private fun applyRealmeWorkaround(builder: Builder) {
        try {
            builder.javaClass.getMethod("setBlocking", Boolean::class.java).invoke(builder, false)
        } catch (e: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.javaClass.getMethod("setAllowBypass", Boolean::class.java).invoke(builder, true)
            }
        } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val chan = NotificationChannel(CHANNEL_ID, "CB Monitor", NotificationManager.IMPORTANCE_LOW)
            nm?.createNotificationChannel(chan)
        }
    }

    private fun createNotification(text: String, conn: Boolean): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CB Panda Monitor")
            .setContentText(text)
            .setSmallIcon(if (conn) android.R.drawable.presence_online else android.R.drawable.presence_busy)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun cleanup() {
        forwardingActive = false
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
