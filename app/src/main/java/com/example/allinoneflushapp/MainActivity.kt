package com.example.allinoneflushapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.URL
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private lateinit var textViewIP: TextView
    private lateinit var textViewDNS: TextView
    private lateinit var networkIndicator: ImageView
    private lateinit var btnDoAllJob: Button
    private lateinit var btnAccessibilityOn: Button
    private lateinit var btnForceCloseAll: Button

    private val pandaPackage = "com.logistics.rider.foodpanda"
    private val dnsList = listOf("1.1.1.1", "8.8.8.8", "8.8.4.4")

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { 
        if (Settings.canDrawOverlays(this)) {
            startFloatingWidget()
        } else {
            Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
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
        btnAccessibilityOn = findViewById(R.id.btnAccessibilityOn)
        btnForceCloseAll = findViewById(R.id.btnForceCloseAll)

        rotateDNS()
        startNetworkMonitor()

        btnDoAllJob.setOnClickListener { doAllJobSequence() }
        btnAccessibilityOn.setOnClickListener { openAccessibilitySettings() }
        btnForceCloseAll.setOnClickListener { forceCloseAllAndExit() }
    }

    private fun startVpnService() {
        val intent = Intent(this, AppMonitorVPNService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "VPN service starting...", Toast.LENGTH_SHORT).show()
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isPandaRunning() && !FloatingWidgetService.isRunning()) {
            checkAndStartFloatingWidget()
        }
    }

    private fun startFloatingWidget() {
        if (!FloatingWidgetService.isRunning()) {
            val intent = Intent(this, FloatingWidgetService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Handler(Looper.getMainLooper()).postDelayed({
                moveTaskToBack(true)
            }, 1000)
        }
    }

    private fun checkAndStartFloatingWidget() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, 
                    Uri.parse("package:$packageName"))
                overlayPermissionLauncher.launch(intent)
            } else {
                startFloatingWidget()
            }
        } else {
            startFloatingWidget()
        }
    }

    private fun isPandaRunning(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val runningApps = activityManager.runningAppProcesses ?: return false
        return runningApps.any { it.processName == pandaPackage }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            Toast.makeText(this, "Enable 'CBP Accessibility Engine'", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun forceCloseAllAndExit() {
        Toast.makeText(this, "Closing all...", Toast.LENGTH_SHORT).show()
        
        if (FloatingWidgetService.isRunning()) {
            stopService(Intent(this, FloatingWidgetService::class.java))
        }
        
        stopService(Intent(this, AppMonitorVPNService::class.java))
        
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        am.killBackgroundProcesses(pandaPackage)
        
        Handler(Looper.getMainLooper()).postDelayed({
            finishAffinity()
            exitProcess(0)
        }, 1500)
    }

    private fun updateIP() {
        CoroutineScope(Dispatchers.IO).launch {
            var ip: String? = null
            try {
                ip = URL("https://api.ipify.org").readText().trim()
            } catch (e: Exception) {
                try {
                    val url = URL("https://1.1.1.1/cdn-cgi/trace")
                    val text = url.readText().trim()
                    val ipLine = text.lines().find { it.startsWith("ip=") }
                    ip = ipLine?.substringAfter("=")?.trim()
                } catch (e2: Exception) {
                    ip = null
                }
            }
            
            withContext(Dispatchers.Main) {
                textViewIP.text = if (ip.isNullOrEmpty()) "IP: —" else "IP: $ip"
            }
        }
    }

    private fun rotateDNS() {
        val selectedDNS = dnsList.random()
        textViewDNS.text = "DNS: $selectedDNS"
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
        // 1. Clear panda
        AccessibilityAutomationService.requestClearAndForceStop(pandaPackage)
        
        CoroutineScope(Dispatchers.Main).launch {
            delay(5000)
            
            // 2. Airplane toggle
            AccessibilityAutomationService.requestToggleAirplane()
            delay(8000)
            
            // 3. Update IP
            updateIP()
            delay(1000)
            
            // 4. Start VPN
            requestVpnPermission()
            delay(4000) // Tunggu VPN stabil
            
            // 5. Launch panda
            launchPandaApp()
            delay(2000)
            
            // 6. Start widget
            checkAndStartFloatingWidget()
        }
    }

    private fun bringAppToForeground() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
    }

    private fun launchPandaApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pandaPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "Panda launched", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch Panda", Toast.LENGTH_SHORT).show()
        }
    }
}
