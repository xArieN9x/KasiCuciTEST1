// Untuk trigger panda dari UI
private fun triggerPandaSelfCheck() {
    Thread {
        try {
            // Connect to own local server
            java.net.Socket("127.0.0.1", 29293).use {
                it.getOutputStream().write(1)
            }
            android.util.Log.d("PANDA", "Self-trigger sent")
        } catch (e: Exception) {
            // Server mungkin belum start
        }
    }.start()
}

// Check panda status
fun isPandaGreen(): Boolean {
    return AppMonitorVPNService.isPandaActive()
}
