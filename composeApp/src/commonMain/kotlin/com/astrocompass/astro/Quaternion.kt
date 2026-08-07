package com.astrocompass.astro

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A unit quaternion representing a 3D rotation, Hamilton convention (w = scalar part).
 * Used for `deviceToSky`: the single rigid rotation that both corrects for phone mounting
 * and maps device-frame directions to sky (ENU) directions.
 */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {

    operator fun times(other: Quaternion) = Quaternion(
        w * other.w - x * other.x - y * other.y - z * other.z,
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
    )

    fun conjugate() = Quaternion(w, -x, -y, -z)

    fun normalized(): Quaternion {
        val len = sqrt(w * w + x * x + y * y + z * z)
        require(len > 1e-12) { "Cannot normalize a near-zero quaternion" }
        return Quaternion(w / len, x / len, y / len, z / len)
    }

    /** Rotates [v] by this quaternion (must be a unit quaternion). */
    fun rotate(v: Vector3): Vector3 {
        val qv = Vector3(x, y, z)
        val t = (qv cross v) * 2.0
        return v + t * w + (qv cross t)
    }

    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

        fun fromAxisAngle(axis: Vector3, angle: Angle): Quaternion {
            val a = axis.normalized()
            val half = angle.radians / 2.0
            val s = sin(half)
            return Quaternion(cos(half), a.x * s, a.y * s, a.z * s)
        }
    }
}
