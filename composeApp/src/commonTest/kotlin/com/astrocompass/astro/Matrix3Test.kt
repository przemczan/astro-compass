package com.astrocompass.astro

import kotlin.test.Test
import kotlin.test.assertEquals

class Matrix3Test {

    @Test
    fun timesMatrix_isAssociativeWithVectorApplication() {
        val a = Matrix3(
            0.0, -1.0, 0.0,
            1.0, 0.0, 0.0,
            0.0, 0.0, 1.0,
        )
        val b = Matrix3(
            1.0, 0.0, 0.0,
            0.0, 0.0, -1.0,
            0.0, 1.0, 0.0,
        )
        val v = Vector3(1.0, 2.0, 3.0)

        val combined = (a * b) * v
        val sequential = a * (b * v)

        assertEquals(sequential.x, combined.x, absoluteTolerance = 1e-12)
        assertEquals(sequential.y, combined.y, absoluteTolerance = 1e-12)
        assertEquals(sequential.z, combined.z, absoluteTolerance = 1e-12)
    }

    @Test
    fun timesIdentity_isNoOp() {
        val m = Matrix3(
            1.0, 2.0, 3.0,
            4.0, 5.0, 6.0,
            7.0, 8.0, 9.0,
        )
        val result = m * Matrix3.IDENTITY
        assertEquals(m, result)
    }
}
