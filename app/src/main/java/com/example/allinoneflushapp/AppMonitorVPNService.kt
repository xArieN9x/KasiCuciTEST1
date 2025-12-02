package com.example.allinoneflushapp

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

class AppMonitorVPNService : VpnService() {

    companion object {
        private var pandaActive = false
        private var dnsIndex = 0
        private var instance: AppMonitorVPNService? = null

        fun isPandaActive() = pandaActive

        fun rotateDNS(dnsList: List<String>) {
            dnsIndex = (dnsIndex + 1) % dnsList.size
            val nextDNS = dnsList[dnsIndex]
            instance?.setDNS(nextDNS)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        startVPN()
        return START_STICKY
    }

    private fun startVPN() {
        val builder = Builder()
        builder.setSession("PandaMonitor")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAllowedApplication("com.logistics.rider.foodpanda")
            .addDnsServer("8.8.8.8")

        vpnInterface = builder.establish()
        pandaActive = true

        monitorTraffic()
    }

    private fun setDNS(dns: String) {
        vpnInterface?.close()
        val builder = Builder()
        builder.setSession("PandaMonitor")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAllowedApplication("com.logistics.rider.foodpanda")
            .addDnsServer(dns)
        vpnInterface = builder.establish()
    }

    private fun monitorTraffic() {
        Thread {
            val input = FileInputStream(vpnInterface?.fileDescriptor)
            val buffer = ByteArray(32767)
            while (true) {
                try {
                    val size = input.read(buffer)
                    pandaActive = size > 0
                } catch (e: Exception) {
                    pandaActive = false
                }
                Thread.sleep(1000)
            }
        }.start()
    }

    override fun onDestroy() {
        vpnInterface?.close()
        pandaActive = false
        super.onDestroy()
    }
}
