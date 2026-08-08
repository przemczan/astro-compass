package com.astrocompass.guiding

import com.astrocompass.astro.Vector3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraMountingTest {

    @Test
    fun everyPreset_isAUnitQuaternion() {
        for (mounting in CameraMounting.entries) {
            val q = mounting.cameraToDevice
            val norm = sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z)
            assertTrue(abs(norm - 1.0) < 1e-9, "${mounting.name}'s cameraToDevice is not a unit quaternion (norm=$norm)")
        }
    }

    @Test
    fun everyPreset_mapsTheBoresightToTheDeviceBackFace() {
        // The in-plane rotation each preset represents is entirely about the boresight axis
        // itself, so it must leave the boresight -> BACK_FACE mapping unchanged no matter which
        // preset is chosen -- only the image's left/right/up/down labeling should vary.
        for (mounting in CameraMounting.entries) {
            val mapped = mounting.cameraToDevice.rotate(Vector3.UNIT_Z)
            assertTrue(
                mapped.angleTo(Vector3(0.0, 0.0, -1.0)).degrees < 1e-6,
                "${mounting.name} does not map the boresight to BACK_FACE: $mapped",
            )
        }
    }

    @Test
    fun everyPreset_isAProperRotation_notAReflection() {
        // A camera mounting can only ever be a rigid rotation -- verify each preset's mapped
        // basis vectors are still right-handed (Xc x Yc = Zc), since a sign error anywhere in
        // the derivation would silently turn this into a reflection instead.
        for (mounting in CameraMounting.entries) {
            val mappedX = mounting.cameraToDevice.rotate(Vector3.UNIT_X)
            val mappedY = mounting.cameraToDevice.rotate(Vector3.UNIT_Y)
            val mappedZ = mounting.cameraToDevice.rotate(Vector3.UNIT_Z)
            val cross = mappedX cross mappedY
            assertTrue(cross.angleTo(mappedZ).degrees < 1e-6, "${mounting.name} is not right-handed: Xc x Yc != Zc")
        }
    }

    @Test
    fun allFourPresets_areDistinctRotations() {
        val angles = CameraMounting.entries.map { it.cameraToDevice.rotate(Vector3.UNIT_X) }
        for (i in angles.indices) {
            for (j in i + 1 until angles.size) {
                assertTrue(
                    angles[i].angleTo(angles[j]).degrees > 1.0,
                    "${CameraMounting.entries[i].name} and ${CameraMounting.entries[j].name} are not distinct",
                )
            }
        }
    }

    @Test
    fun default_isRotation0() {
        assertEquals(CameraMounting.ROTATION_0, CameraMounting.DEFAULT)
    }
}
