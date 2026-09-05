val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
val ssagManager = OrionSSAGManager(usbManager)

// 1. Wykrycie urządzenia po podłączeniu przez USB OTG
val device: UsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)!!
val state = ssagManager.attachDevice(device)

if (state == OrionSSAGManager.CameraState.BOOT_MODE) {
    // Odczytaj plik ssag.fw umieszczony w folderze app/src/main/assets/ssag.fw
    val hexContent = assets.open("ssag.fw").bufferedReader().use { it.readText() }
    
    // Wgraj firmware do Cypress FX2
    val success = ssagManager.uploadFirmware(hexContent)
    if (success) {
        // Kamera zre-enumeruje się automatycznie i zostanie ponownie wykryta jako PID 0x0005
    }
} else if (state == OrionSSAGManager.CameraState.READY) {
    // 2. Kamera jest gotowa
    if (ssagManager.openCamera()) {
        ssagManager.setGain(5)
        ssagManager.setExposureMs(500) // 0.5s

        // 3. Pobranie zdjęcia w osobnym wątku (wykonanie bulkTransfer na UI Thread zablokuje ekran)
        Thread {
            val bitmap = ssagManager.captureFrame()
            runOnUiThread {
                bitmap?.let {
                    imageView.setImageBitmap(it) // Wyświetlenie zdjęcia na ekranie
                }
            }
        }.start()
    }
}