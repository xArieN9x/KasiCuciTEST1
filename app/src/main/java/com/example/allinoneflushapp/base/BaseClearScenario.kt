package com.example.allinoneflushapp.base

object BaseClearScenarioText {
    // Force stop dialog titles / buttons
    val arrayTextForceStopDialogTitle = arrayListOf<CharSequence>(
        "Force stop this app?",
        "Force stop this application?",
        "Paksa hentikan aplikasi?",
        "Benamkan aplikasi?" // (fallback)
    )

    // Buttons (dialog and app info)
    val arrayTextForceStopButton = arrayListOf<CharSequence>(
        "Force stop",
        "Force Stop",
        "Paksa berhenti",
        "Paksa hentikan"
    )

    val arrayTextCancelButton = arrayListOf<CharSequence>(
        "Cancel",
        "Batal",
        "Tidak",
        "Tutup"
    )

    val arrayTextOkButton = arrayListOf<CharSequence>(
        "OK",
        "Yes",
        "Confirm",
        "Ya",
        "Force stop" // include in case dialog uses same label
    )

    // Clear cache / storage menu labels
    val arrayTextClearCacheButton = arrayListOf<CharSequence>(
        "Clear cache",
        "Clear Cache",
        "Kosongkan cache",
        "Bersihkan cache"
    )

    val arrayTextStorageAndCacheMenu = arrayListOf<CharSequence>(
        "Storage usage",
        "Storage",
        "Storage & cache",
        "App storage",
        "Storage usage"
    )

    // Clear data labels
    val arrayTextClearDataButton = arrayListOf<CharSequence>(
        "Clear data",
        "Clear storage",
        "Delete data",
        "Kosongkan data"
    )

    val arrayTextDeleteButton = arrayListOf<CharSequence>(
        "Delete",
        "Delete anyway",
        "OK",
        "Yes",
        "Delete"
    )

    val arrayTextClearDataDialogTitle = arrayListOf<CharSequence>(
        "Delete app data?",
        "Clear data?",
        "Hapus data aplikasi?"
    )
}
