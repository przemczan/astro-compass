package com.astrocompass.astro

/** A 3x3 matrix in row-major order (m[row][col]). */
data class Matrix3(
    val m00: Double, val m01: Double, val m02: Double,
    val m10: Double, val m11: Double, val m12: Double,
    val m20: Double, val m21: Double, val m22: Double,
) {
    operator fun plus(other: Matrix3) = Matrix3(
        m00 + other.m00, m01 + other.m01, m02 + other.m02,
        m10 + other.m10, m11 + other.m11, m12 + other.m12,
        m20 + other.m20, m21 + other.m21, m22 + other.m22,
    )

    operator fun times(scalar: Double) = Matrix3(
        m00 * scalar, m01 * scalar, m02 * scalar,
        m10 * scalar, m11 * scalar, m12 * scalar,
        m20 * scalar, m21 * scalar, m22 * scalar,
    )

    operator fun times(v: Vector3) = Vector3(
        m00 * v.x + m01 * v.y + m02 * v.z,
        m10 * v.x + m11 * v.y + m12 * v.z,
        m20 * v.x + m21 * v.y + m22 * v.z,
    )

    fun transposed() = Matrix3(
        m00, m10, m20,
        m01, m11, m21,
        m02, m12, m22,
    )

    val trace: Double get() = m00 + m11 + m22

    companion object {
        val IDENTITY = Matrix3(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        )
    }
}
