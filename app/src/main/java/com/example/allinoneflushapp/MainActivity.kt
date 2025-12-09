package com.example.allinoneflushapp

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var textViewIP: TextView
    private lateinit var textViewDNS: TextView
    private lateinit var networkIndicator: ImageView
    private lateinit var btnDoAllJob: Button
    private lateinit var btnStartVpn: Button

    private val pandaPackage = "com.logistics.rider.foodpanda"
    private val dnsList = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "9.9.9.10")

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startService(Intent(this, AppMonitorVPNService::class.java))
            AppMonitorVPNService.rotateDNS(dnsList)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGlobals.applicationContext = application
        setContentView(R.layout.activity_main)

        textViewIP = findViewById(R.id.textViewIP)
        textViewDNS = findViewById(R.id.textViewDNS)
        networkIndicator = findViewById(R.id.networkIndicator)
        btnDoAllJob = findViewById(R.id.btnDoAllJob)
        btnStartVpn = findViewById(R.id.btnStartVpn)

        updateIP()
        rotateDNS()
        startNetworkMonitor()

        btnDoAllJob.setOnClickListener { doAllJobSequence() }
        btnStartVpn.setOnClickListener { requestVpnPermission() }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startService(Intent(this, AppMonitorVPNService::class.java))
            AppMonitorVPNService.rotateDNS(dnsList)
        }
    }

    private fun updateIP() {
        CoroutineScope(Dispatchers.IO).launch {
            val ip = try {
                URL("https://api.ipify.org").readText().trim()
            } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                textViewIP.text = if (ip.isNullOrEmpty()) "Public IP: —" else "Public IP: $ip"
            }
        }
    }

    private fun rotateDNS() {
        val selectedDNS = dnsList.random()
        textViewDNS.text = "DNS: $selectedDNS"
        AppMonitorVPNService.rotateDNS(dnsList = listOf(selectedDNS))
    }

    private fun startNetworkMonitor() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val connected = AppMonitorVPNService.isPandaActive()
                withContext(Dispatchers.Main) {
                    networkIndicator.setImageResource(
                        if (connected) R.drawable.green_circle else R.drawable.red_circle
                    )
                }
                delay(1500)
            }
        }
    }

    private fun doAllJobSequence() {
        // 1. Force stop Panda + clear cache
        AccessibilityAutomationService.requestClearAndForceStop(pandaPackage)

        CoroutineScope(Dispatchers.Main).launch {
            // ✅ OPTIMIZED: 3000ms → 2500ms (faster flow)
            delay(2500)
            
            // 2. Toggle airplane
            AccessibilityAutomationService.requestToggleAirplane()
            
            // ✅ OPTIMIZED: 7000ms → 5000ms (network recovery faster)
            delay(5000)
            
            // 3. Setup VPN
            requestVpnPermission()
            
            // ✅ OPTIMIZED: 2000ms → 1000ms (tunnel establish faster)
            delay(1000)
            
            // 4. Rotate DNS & refresh IP
            rotateDNS()
            updateIP()
            
            // 5. Launch Panda app immediately
            delay(500)
            launchPandaApp()
        }
    }

    private fun launchPandaApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pandaPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (e: Exception) {
            // Panda not installed or error
        }
    }
}
