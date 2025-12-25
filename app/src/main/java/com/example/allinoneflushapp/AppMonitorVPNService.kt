package com.example.allinoneflushapp

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AppMonitorVPNService : VpnService() {

    companion object {
        private var pandaActive = false
        fun isPandaActive() = pandaActive
        fun rotateDNS(dnsList: List<String>) {}
          // dummy – future use
    }
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var running = false

    private val CHANNEL_ID = "cb_monitor"
    private val NOTIF_ID = 1001

    // ✅ Realme-safe executor
    private val executor = ThreadPoolExecutor(
        2, 8,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(100)
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotif("CB Monitor starting…", false))

        val builder = Builder()
            .setSession("CB Panda Monitor")
            .setMtu(1500)
            .addAddress("10.215.173.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")

        // ❌ exclude Chrome sahaja
        try { builder.addDisallowedApplication("com.android.chrome") } catch (_: Exception) {}

        applyRealmeWorkaround(builder)

        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        vpnInterface ?: return START_NOT_STICKY

        running = true
        pandaActive = true

        startTunReader()

        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotif("CB Monitor Active ✅", true))

        return START_STICKY
    }

    // =========================================================
    // 🧠 TUN READER (MONITOR MODE)
    // =========================================================
    private fun startTunReader() {
        Thread {
            val fd = vpnInterface?.fileDescriptor ?: return@Thread
            val input = FileInputStream(fd)
            val buffer = ByteArray(32767)

            while (running) {
                val len = input.read(buffer)
                if (len <= 0) continue

                pandaActive = true

                val version = (buffer[0].toInt() shr 4) and 0xF
                if (version != 4) continue

                val protocol = buffer[9].toInt() and 0xFF
                val srcIp = ip(buffer, 12)
                val dstIp = ip(buffer, 16)

                when (protocol) {
                    6 -> handleTCP(buffer, len, srcIp, dstIp)
                    17 -> handleUDP(buffer, len, srcIp, dstIp)
                }
            }
        }.start()
    }

    // =========================================================
    // 🟢 TCP = MONITOR + RELAY (NO WRITE BACK TO TUN)
    // =========================================================
    private fun handleTCP(pkt: ByteArray, len: Int, srcIp: String, dstIp: String) {
        if (len < 40) return

        val srcPort = port(pkt, 20)
        val dstPort = port(pkt, 22)

        val ihl = (pkt[0].toInt() and 0x0F) * 4
        val tcpHdr = ((pkt[ihl + 12].toInt() shr 4) and 0xF) * 4
        val payloadOffset = ihl + tcpHdr
        val payloadLen = len - payloadOffset

        if (payloadLen <= 0) return

        val payload = pkt.copyOfRange(payloadOffset, len)

        executor.execute {
            try {
                val socket = Socket(dstIp, dstPort)
                protect(socket)

                socket.getOutputStream().write(payload)
                socket.getOutputStream().flush()

                // 🔥 MONITOR ONLY
                android.util.Log.d(
                    "CB_TCP",
                    "TCP ${srcIp}:${srcPort} -> ${dstIp}:${dstPort} | ${payloadLen} bytes"
                )

                socket.close()
            } catch (e: Exception) {
                android.util.Log.w("CB_TCP", "TCP relay fail: ${e.message}")
            }
        }
    }

    // =========================================================
    // 🟢 UDP (DNS SAFE)
    // =========================================================
    private fun handleUDP(pkt: ByteArray, len: Int, srcIp: String, dstIp: String) {
        if (len < 28) return

        val dstPort = port(pkt, 22)
        val payload = pkt.copyOfRange(28, len)

        executor.execute {
            try {
                val socket = DatagramSocket()
                protect(socket)

                socket.send(
                    DatagramPacket(
                        payload,
                        payload.size,
                        InetAddress.getByName(dstIp),
                        dstPort
                    )
                )

                socket.close()

                android.util.Log.d(
                    "CB_UDP",
                    "UDP ${srcIp} -> ${dstIp}:${dstPort} | ${payload.size} bytes"
                )
            } catch (e: Exception) {
                android.util.Log.w("CB_UDP", "UDP fail: ${e.message}")
            }
        }
    }

    // =========================================================
    // 🧩 HELPERS
    // =========================================================
    private fun ip(b: ByteArray, o: Int): String =
        "${b[o].toInt() and 0xFF}.${b[o+1].toInt() and 0xFF}.${b[o+2].toInt() and 0xFF}.${b[o+3].toInt() and 0xFF}"

    private fun port(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o+1].toInt() and 0xFF)

    private fun applyRealmeWorkaround(builder: Builder) {
        try { builder.javaClass.getMethod("setBlocking", Boolean::class.java).invoke(builder, false) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.javaClass.getMethod("setAllowBypass", Boolean::class.java).invoke(builder, true)
            }
        } catch (_: Exception) {}
    }

    // =========================================================
    // 🔔 NOTIFICATION
    // =========================================================
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "CB Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(chan)
        }
    }

    private fun buildNotif(text: String, ok: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CB Panda Monitor")
            .setContentText(text)
            .setSmallIcon(if (ok) android.R.drawable.presence_online else android.R.drawable.presence_busy)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        running = false
        pandaActive = false
        vpnInterface?.close()
        stopForeground(true)
        super.onDestroy()
    }
}
