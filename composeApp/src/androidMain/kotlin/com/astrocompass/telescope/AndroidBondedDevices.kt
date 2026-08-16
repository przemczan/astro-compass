package com.astrocompass.telescope

import android.bluetooth.BluetoothManager
import android.content.Context

/** Already-paired Bluetooth Classic devices, as (address, display name) pairs -- pairing itself
 *  happens in Android's own Bluetooth settings, not this app, so this is read-only. Empty if
 *  Bluetooth is unavailable/disabled or [hasBluetoothConnectPermission] is false, never throws. */
fun bondedTelescopeCandidates(context: Context): List<Pair<String, String>> {
    if (!hasBluetoothConnectPermission(context)) return emptyList()
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return emptyList()
    if (!adapter.isEnabled) return emptyList()
    return runCatching {
        adapter.bondedDevices.map { device -> device.address to (device.name ?: device.address) }
    }.getOrDefault(emptyList())
}
