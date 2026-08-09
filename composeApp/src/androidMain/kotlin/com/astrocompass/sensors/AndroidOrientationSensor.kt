package com.astrocompass.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.astrocompass.astro.Quaternion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    private val _magneticDeviceToWorld = MutableStateFlow<Quaternion?>(null)
    override val magneticDeviceToWorld: StateFlow<Quaternion?> = _magneticDeviceToWorld

    private val activeSensorType = activeSource.toAndroidSensorType()

    /** [SensorSource.GAME_ROTATION_VECTOR] is the only source whose world frame is *not*
     *  magnetometer-referenced, so it is the only one needing `TYPE_ROTATION_VECTOR` registered
     *  alongside it -- at a slower rate, since it only has to track a slowly-changing yaw offset
     *  rather than the telescope's motion. Every other source is its own magnetic reference,
     *  which is both cheaper and exact (no two-stream sampling lag to leak into the yaw). */
    private val needsSeparateMagneticStream =
        capabilities.hasMagnetometer && activeSource == SensorSource.GAME_ROTATION_VECTOR

    override fun start() {
        sensorManager.getDefaultSensor(activeSensorType)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (needsSeparateMagneticStream) {
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
            activeSensorType -> {
                _orientation.value = DeviceOrientation(quaternion, activeSource, event.timestamp)
                if (!needsSeparateMagneticStream && capabilities.hasMagnetometer) {
                    _magneticDeviceToWorld.value = quaternion
                }
            }

            Sensor.TYPE_ROTATION_VECTOR -> _magneticDeviceToWorld.value = quaternion
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

    private fun SensorSource.toAndroidSensorType(): Int = when (this) {
        SensorSource.GAME_ROTATION_VECTOR -> Sensor.TYPE_GAME_ROTATION_VECTOR
        SensorSource.ROTATION_VECTOR -> Sensor.TYPE_ROTATION_VECTOR
        SensorSource.GEOMAGNETIC_ROTATION_VECTOR -> Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
    }
}
