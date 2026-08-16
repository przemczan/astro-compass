package com.astrocompass.telescope

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "BluetoothTelescope"
private const val READ_CHUNK_SIZE = 256

/** The standard Serial Port Profile UUID -- what every RFCOMM serial-over-Bluetooth device
 *  (DSC boxes, GoTo hand controllers, OnStep's Bluetooth adapters) registers its service under. */
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/**
 * [TelescopeTransport] over Bluetooth Classic SPP (RFCOMM) -- plain `android.bluetooth`, no
 * third-party BLE/serial library, matching [AndroidLocationProvider][
 * com.astrocompass.location.AndroidLocationProvider]/[AndroidCameraCapture][
 * com.astrocompass.platesolve.AndroidCameraCapture]'s "plain platform SDK" convention. Only
 * connects to an already-paired ([deviceAddress] from [bondedTelescopeCandidates]) device --
 * there is no discovery flow, so no `BLUETOOTH_SCAN` permission is needed.
 */
class AndroidBluetoothTelescopeTransport(
    private val context: Context,
    private val deviceAddress: String,
) : TelescopeTransport {
    private val _state = MutableStateFlow(TelescopeTransportState.IDLE)
    override val state: StateFlow<TelescopeTransportState> = _state

    private var socket: BluetoothSocket? = null

    override suspend fun connect() {
        _state.value = TelescopeTransportState.CONNECTING

        if (!hasBluetoothConnectPermission(context)) {
            Log.w(TAG, "connect: BLUETOOTH_CONNECT permission not granted")
            _state.value = TelescopeTransportState.FAILED
            return
        }

        val newSocket = withContext(Dispatchers.IO) {
            runCatching {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: error("No Bluetooth adapter on this device")
                val device = adapter.getRemoteDevice(deviceAddress)
                val socketToServer = device.createRfcommSocketToServiceRecord(SPP_UUID)
                // No adapter.cancelDiscovery() here -- this transport never starts discovery
                // (only ever connects to an already-paired device), so there is nothing to
                // cancel, and the call itself requires BLUETOOTH_SCAN on API 31+, a permission
                // this app deliberately never requests (see the class doc).
                socketToServer.connect()
                socketToServer
            }.onFailure { Log.w(TAG, "connect failed", it) }.getOrNull()
        }

        if (newSocket == null) {
            _state.value = TelescopeTransportState.FAILED
            return
        }
        socket = newSocket
        _state.value = TelescopeTransportState.CONNECTED
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { runCatching { socket?.close() } }
        socket = null
        _state.value = TelescopeTransportState.DISCONNECTED
    }

    override suspend fun write(bytes: ByteArray) {
        val activeSocket = socket ?: error("AndroidBluetoothTelescopeTransport.write called while not connected")
        try {
            withContext(Dispatchers.IO) { activeSocket.outputStream.write(bytes) }
        } catch (e: Exception) {
            _state.value = TelescopeTransportState.FAILED
            throw e
        }
    }

    /** Mirrors [TcpTelescopeTransport.readAvailable]'s contract: throws (after flipping [state]
     *  to [TelescopeTransportState.FAILED]) rather than returning an empty array on failure or
     *  peer-closed, since [Lx200Session]'s read loops would spin forever on a non-suspending
     *  "nothing happened" result. [BluetoothSocket]'s plain [java.io.InputStream] has no
     *  non-blocking "read whatever's available" primitive, so this blocks a `Dispatchers.IO`
     *  thread for the single [java.io.InputStream.read] call, same as [write]. */
    override suspend fun readAvailable(): ByteArray {
        val activeSocket = socket ?: error("AndroidBluetoothTelescopeTransport.readAvailable called while not connected")
        return try {
            withContext(Dispatchers.IO) {
                val buffer = ByteArray(READ_CHUNK_SIZE)
                val count = activeSocket.inputStream.read(buffer)
                if (count < 0) error("Telescope connection closed by peer")
                buffer.copyOf(count)
            }
        } catch (e: Exception) {
            _state.value = TelescopeTransportState.FAILED
            throw e
        }
    }
}

/** `BLUETOOTH_CONNECT` is a runtime permission only from API 31 onward -- older API levels rely
 *  solely on the install-time legacy `BLUETOOTH` permission declared in the manifest, so there is
 *  nothing to check at runtime below that. */
internal fun hasBluetoothConnectPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
