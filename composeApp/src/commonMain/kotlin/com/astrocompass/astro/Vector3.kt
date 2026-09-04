package com.astrocompass.astro

import kotlin.math.sqrt

/** A general-purpose 3D vector. Used both for plain math and for unit vectors in the ENU sky frame. */
data class Vector3(val x: Double, val y: Double, val z: Double) {

    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun unaryMinus() = Vector3(-x, -y, -z)
    operator fun times(scalar: Double) = Vector3(x * scalar, y * scalar, z * scalar)

    infix fun dot(other: Vector3): Double = x * other.x + y * other.y + z * other.z

    infix fun cross(other: Vector3): Vector3 = Vector3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    /** Outer product a⊗b, as used to accumulate the Davenport q-method's B matrix. */
    infix fun outer(other: Vector3): Matrix3 = Matrix3(
        x * other.x, x * other.y, x * other.z,
        y * other.x, y * other.y, y * other.z,
        z * other.x, z * other.y, z * other.z,
    )

    val lengthSquared: Double get() = x * x + y * y + z * z
    val length: Double get() = sqrt(lengthSquared)

    fun normalized(): Vector3 {
        val len = length
        require(len > 1e-12) { "Cannot normalize a near-zero vector" }
        return Vector3(x / len, y / len, z / len)
    }

    /** Angle between this and [other], both treated as directions (not necessarily unit length). */
    fun angleTo(other: Vector3): Angle {
        val cosAngle = (this dot other) / (length * other.length)
        return Angle.ofRadians(kotlin.math.acos(cosAngle.coerceIn(-1.0, 1.0)))
    }

    companion object {
        val ZERO = Vector3(0.0, 0.0, 0.0)
        val UNIT_X = Vector3(1.0, 0.0, 0.0)
        val UNIT_Y = Vector3(0.0, 1.0, 0.0)
        val UNIT_Z = Vector3(0.0, 0.0, 1.0)
    }
}
