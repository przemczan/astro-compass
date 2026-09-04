package com.astrocompass.astro

import kotlin.test.Test
import kotlin.test.assertEquals

class Vector3Test {

    @Test
    fun slerp_atEndpoints_returnsInputDirectionsUnchanged() {
        val a = Vector3(1.0, 0.0, 0.0)
        val b = Vector3(0.0, 1.0, 0.0)

        val atStart = a.slerp(b, 0.0)
        val atEnd = a.slerp(b, 1.0)

        assertEquals(a.x, atStart.x, absoluteTolerance = 1e-12)
        assertEquals(a.y, atStart.y, absoluteTolerance = 1e-12)
        assertEquals(a.z, atStart.z, absoluteTolerance = 1e-12)
        assertEquals(b.x, atEnd.x, absoluteTolerance = 1e-12)
        assertEquals(b.y, atEnd.y, absoluteTolerance = 1e-12)
        assertEquals(b.z, atEnd.z, absoluteTolerance = 1e-12)
    }

    @Test
    fun slerp_midpoint_bisectsTheAngleAndStaysUnitLength() {
        val a = Vector3(1.0, 0.0, 0.0)
        val b = Vector3(0.0, 1.0, 0.0)

        val midpoint = a.slerp(b, 0.5)

        assertEquals(1.0, midpoint.length, absoluteTolerance = 1e-12)
        assertEquals(a.angleTo(midpoint).degrees, midpoint.angleTo(b).degrees, absoluteTolerance = 1e-9)
        assertEquals(a.angleTo(b).degrees, a.angleTo(midpoint).degrees + midpoint.angleTo(b).degrees, absoluteTolerance = 1e-9)
    }

    @Test
    fun slerp_identicalDirections_returnsThatDirectionForAnyT() {
        val a = Vector3(0.3, 0.4, 0.5).normalized()

        val result = a.slerp(a, 0.7)

        assertEquals(a.x, result.x, absoluteTolerance = 1e-12)
        assertEquals(a.y, result.y, absoluteTolerance = 1e-12)
        assertEquals(a.z, result.z, absoluteTolerance = 1e-12)
    }
}
