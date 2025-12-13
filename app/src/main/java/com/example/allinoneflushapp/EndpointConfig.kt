package com.example.allinoneflushapp

object EndpointConfig {
    // Critical endpoints - HIGH priority
    val CRITICAL_ENDPOINTS = setOf(
        "my.usehurrier.com",      // Order data
        "api.mapbox.com",         // Maps
        "firebaseremoteconfigrealtime.googleapis.com" // Real-time config
    )
    
    // Background endpoints - LOW priority  
    val BACKGROUND_ENDPOINTS = setOf(
        "firebaselogging-pa.googleapis.com", // Logging
        "ingest.us.sentry.io"                // Error reporting
    )
    
    // Connection keep-alive time (milliseconds)
    const val CRITICAL_KEEP_ALIVE = 120000L    // 2 minutes
    const val NORMAL_KEEP_ALIVE = 60000L       // 1 minute
    const val BACKGROUND_KEEP_ALIVE = 30000L   // 30 seconds
    
    // Maximum connections per endpoint
    const val MAX_CONNECTIONS_PER_ENDPOINT = 3
}
