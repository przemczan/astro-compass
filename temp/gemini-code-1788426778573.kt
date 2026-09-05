package com.astro.ssag

import android.graphics.Bitmap
import android.hardware.usb.*
import android.util.Log
import java.nio.ByteBuffer

class OrionSSAGManager(private val usbManager: UsbManager) {

    enum class CameraState {
        DISCONNECTED,
        BOOT_MODE,        // Wymaga załadowania firmware
        READY             // Firmware wgrany, gotowa do zdjęć
    }

    companion object {
        private const val TAG = "OrionSSAG"

        const val VENDOR_ID = 0x1820
        const val PRODUCT_ID_BOOT = 0x0001
        const val PRODUCT_ID_READY = 0x0005

        // Parametry obrazu SSAG (Aptina MT9M001)
        const val IMAGE_WIDTH = 1280
        const val IMAGE_HEIGHT = 1024
        const val RAW_FRAME_SIZE = IMAGE_WIDTH * IMAGE_HEIGHT

        // Cypress FX2 Anchor Reqs
        private const val ANCHOR_LOAD_INTERNAL = 0xA0
        private const val CPUCS_REG = 0xE600

        // Komendy Vendor SSAG / Rejestry I2C
        private const val VR_I2C_WRITE = 0xDB
        private const val VR_TRIGGER_EXP = 0xE0

        private const val REG_GAIN_GLOBAL = 0x35
        private const val REG_EXPOSURE = 0x09
    }

    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkInEndpoint: UsbEndpoint? = null

    var currentState: CameraState = CameraState.DISCONNECTED
        private set

    /**
     * Podłączenie urządzenia USB
     */
    fun attachDevice(device: UsbDevice): CameraState {
        if (device.vendorId != VENDOR_ID) return CameraState.DISCONNECTED

        this.usbDevice = device
        currentState = when (device.productId) {
            PRODUCT_ID_BOOT -> CameraState.BOOT_MODE
            PRODUCT_ID_READY -> CameraState.READY
            else -> CameraState.DISCONNECTED
        }
        return currentState
    }

    /**
     * Wgrywanie firmware'u Hex do Cypress FX2 (wymaga trybu BOOT)
     */
    fun uploadFirmware(intelHexContent: String): Boolean {
        val device = usbDevice ?: return false
        if (currentState != CameraState.BOOT_MODE) return false
        if (!usbManager.hasPermission(device)) return false

        val connection = usbManager.openDevice(device) ?: return false

        try {
            // 1. Wstrzymanie procesora 8051
            setCpuReset(connection, true)

            // 2. Parsowanie i wgrywanie rekordów HEX
            val records = IntelHexParser.parse(intelHexContent)
            for (record in records) {
                val transferred = connection.controlTransfer(
                    0x40, // Vendor Request OUT
                    ANCHOR_LOAD_INTERNAL,
                    record.address,
                    0x0000,
                    record.data,
                    record.data.size,
                    1000
                )
                if (transferred < 0) {
                    Log.e(TAG, "Błąd wgrywania bloku firmware pod adres: ${record.address}")
                    return false
                }
            }

            // 3. Wznowienie pracy 8051 (kamera zre-enumeruje się jako PID 0x0005)
            setCpuReset(connection, false)
            Log.i(TAG, "Firmware wgrany pomyślnie. Oczekiwanie na ponowne podłączenie USB...")
            return true
        } finally {
            connection.close()
        }
    }

    /**
     * Otwiera sesję połączenia z kamerą w trybie READY
     */
    fun openCamera(): Boolean {
        val device = usbDevice ?: return false
        if (currentState != CameraState.READY) return false
        if (!usbManager.hasPermission(device)) return false

        val connection = usbManager.openDevice(device) ?: return false
        val iface = device.getInterface(0)

        if (!connection.claimInterface(iface, true)) return false

        // Znajdź Endpoint Bulk IN (odczyt danych obrazu)
        var endpoint: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                endpoint = ep
                break
            }
        }

        if (endpoint == null) return false

        this.usbConnection = connection
        this.usbInterface = iface
        this.bulkInEndpoint = endpoint

        // Domyślna inicjalizacja matrycy
        setGain(1)
        setExposureMs(100)

        return true
    }

    /**
     * Ustawia wzmocnienie (Gain) matrycy MT9M001 (zakres 1 - 15)
     */
    fun setGain(gain: Int): Boolean {
        val connection = usbConnection ?: return false
        val safeGain = gain.coerceIn(1, 15)
        val data = byteArrayOf(0x00, safeGain.toByte())
        
        return writeI2CRegister(connection, REG_GAIN_GLOBAL, data)
    }

    /**
     * Ustawia czas ekspozycji w milisekundach
     */
    fun setExposureMs(ms: Int): Boolean {
        val connection = usbConnection ?: return false
        val regVal = (ms * 12).coerceIn(1, 65535) // Skalowanie dla zegara MT9M001
        
        val data = byteArrayOf(
            ((regVal shr 8) and 0xFF).toByte(),
            (regVal and 0xFF).toByte()
        )
        return writeI2CRegister(connection, REG_EXPOSURE, data)
    }

    /**
     * Wykonuje naświetlanie i zwraca klatkę jako gotowy obiekt Android Bitmap (Skala szarości)
     */
    fun captureFrame(): Bitmap? {
        val connection = usbConnection ?: return null
        val ep = bulkInEndpoint ?: return null

        val rawBuffer = ByteArray(RAW_FRAME_SIZE)

        // 1. Trigger ekspozycji
        val trigger = byteArrayOf(0x01)
        val triggerRes = connection.controlTransfer(0x40, VR_TRIGGER_EXP, 0x0000, 0x0000, trigger, trigger.size, 1000)
        if (triggerRes < 0) return null

        // 2. Pobranie danych przez Bulk Transfer
        var totalBytesRead = 0
        val timeout = 5000

        while (totalBytesRead < RAW_FRAME_SIZE) {
            val bytesToRead = RAW_FRAME_SIZE - totalBytesRead
            val read = connection.bulkTransfer(ep, rawBuffer, totalBytesRead, bytesToRead, timeout)
            if (read < 0) {
                Log.e(TAG, "Błąd transferu danych z kamery na poziomie bajtu: $totalBytesRead")
                return null
            }
            totalBytesRead += read
        }

        // 3. Konwersja surowych bajtów RAW (8-bit Skala Szarości) do Bitmapy Androida
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ALPHA_8)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rawBuffer))
        return bitmap
    }

    /**
     * Zamyka połączenie z kamerą
     */
    fun close() {
        usbConnection?.let {
            usbInterface?.let { iface -> it.releaseInterface(iface) }
            it.close()
        }
        usbConnection = null
        currentState = CameraState.DISCONNECTED
    }

    // --- Metody Prywatne Pomocnicze ---

    private fun setCpuReset(connection: UsbDeviceConnection, reset: Boolean) {
        val data = byteArrayOf(if (reset) 0x01.toByte() else 0x00.toByte())
        connection.controlTransfer(0x40, ANCHOR_LOAD_INTERNAL, CPUCS_REG, 0x0000, data, data.size, 1000)
    }

    private fun writeI2CRegister(connection: UsbDeviceConnection, register: Int, data: ByteArray): Boolean {
        val res = connection.controlTransfer(
            0x40,
            VR_I2C_WRITE,
            register,
            0x0000,
            data,
            data.size,
            1000
        )
        return res >= 0
    }
}