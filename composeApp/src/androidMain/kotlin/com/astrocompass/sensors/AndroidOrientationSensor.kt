package com.astrocompass.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.astrocompass.astro.Angle
import com.astrocompass.astro.Quaternion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.atan2

/**
 * Android's rotation-vector family of sensors already reports device-to-world orientation in a
 * frame whose Z axis is gravity-referenced "up" (matching this app's ENU sky frame); X/Y are an
 * arbitrary horizontal reference for [SensorSource.GAME_ROTATION_VECTOR] (that's the drift),
 * magnetic-north-referenced for [SensorSource.ROTATION_VECTOR]. See Android's
 * `SensorEvent.TYPE_ROTATION_VECTOR` docs for the world-frame definition this relies on.
 *
 * [overrideSource] is read once, at construction (from Settings, by `MainActivity`, before the
 * rest of the app's services exist) -- the Advanced-settings override takes effect on the next
 * app restart rather than live, trading a small UX rough edge for not having to re-register
 * Android sensor listeners mid-session.
 */
class AndroidOrientationSensor(context: Context, overrideSource: SensorSource? = null) : OrientationSensor, SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override val capabilities = SensorCapabilities(
        hasGyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
        hasMagnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null,
        hasAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
    )
    override val activeSource: SensorSource = overrideSource ?: capabilities.defaultSource()

    private val _orientation = MutableStateFlow<DeviceOrientation?>(null)
    override val orientation: StateFlow<DeviceOrientation?> = _orientation

    private val _compassBootstrapAzimuth = MutableStateFlow<Angle?>(null)
    override val compassBootstrapAzimuth: StateFlow<Angle?> = _compassBootstrapAzimuth

    private val activeSensorType = activeSource.toAndroidSensorType()
    private val needsSeparateCompassBootstrap = capabilities.hasMagnetometer && activeSource != SensorSource.ROTATION_VECTOR

    override fun start() {
        sensorManager.getDefaultSensor(activeSensorType)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (needsSeparateCompassBootstrap) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val quaternion = event.values.toQuaternion()
        when (event.sensor.type) {
            activeSensorType -> _orientation.value = DeviceOrientation(quaternion, activeSource, event.timestamp)
            Sensor.TYPE_ROTATION_VECTOR -> if (needsSeparateCompassBootstrap) {
                _compassBootstrapAzimuth.value = quaternion.worldAzimuth()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun FloatArray.toQuaternion(): Quaternion {
        val x = this[0].toDouble()
        val y = this[1].toDouble()
        val z = this[2].toDouble()
        val w = if (size >= 4) this[3].toDouble() else kotlin.math.sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
        return Quaternion(w, x, y, z).normalized()
    }

    /** Azimuth of the device's own +Y (top edge) axis, world-frame -- a reasonable single number
     *  for "which way is this phone roughly facing" before any star sync exists. */
    private fun Quaternion.worldAzimuth(): Angle {
        val topEdgeInWorld = rotate(com.astrocompass.astro.Vector3(0.0, 1.0, 0.0))
        return Angle.ofRadians(atan2(topEdgeInWorld.x, topEdgeInWorld.y)).normalized()
    }

    private fun SensorSource.toAndroidSensorType(): Int = when (this) {
        SensorSource.GAME_ROTATION_VECTOR -> Sensor.TYPE_GAME_ROTATION_VECTOR
        SensorSource.ROTATION_VECTOR -> Sensor.TYPE_ROTATION_VECTOR
        SensorSource.GEOMAGNETIC_ROTATION_VECTOR -> Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
    }
}
