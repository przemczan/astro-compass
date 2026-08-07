package com.astrocompass.sensors

import com.astrocompass.astro.Quaternion

/** A single orientation reading. [deviceToWorld] rotates a vector expressed in the phone's body
 *  frame into the sensor's own reference frame -- gravity-referenced "up" always, horizontal
 *  reference arbitrary (and, for [SensorSource.GAME_ROTATION_VECTOR], drifting). */
data class DeviceOrientation(
    val deviceToWorld: Quaternion,
    val source: SensorSource,
    val timestampMillis: Long,
)
