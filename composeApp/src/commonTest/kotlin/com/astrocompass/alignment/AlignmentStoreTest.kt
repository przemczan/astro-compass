package com.astrocompass.alignment

import com.astrocompass.astro.Quaternion
import com.astrocompass.astro.Vector3
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlignmentStoreTest {

    private val samplePoint = AlignmentPoint(
        skyDirection = Vector3(0.0, 1.0, 0.0),
        sensorDirection = Vector3(0.1, 0.99, 0.0).normalized(),
        capturedAtEpochMillis = 12345L,
        targetId = "Vega",
        source = AlignmentSource.MANUAL_SYNC,
    )
    private val sampleModel = AlignmentModel(
        sensorToSky = Quaternion.fromAxisAngle(Vector3.UNIT_Z, com.astrocompass.astro.Angle.ofDegrees(10.0)),
        points = listOf(samplePoint),
        rmsResidualDegrees = 0.25,
        computedAtEpochMillis = 999L,
    )

    @Test
    fun loadBeforeSave_returnsNull() {
        val store = AlignmentStore(MapSettings())
        assertNull(store.load())
    }

    @Test
    fun saveThenLoad_roundTripsExactly() {
        val store = AlignmentStore(MapSettings())
        store.save(sampleModel)

        val loaded = store.load()
        assertEquals(sampleModel, loaded)
    }

    @Test
    fun clear_removesTheSavedModel() {
        val store = AlignmentStore(MapSettings())
        store.save(sampleModel)
        store.clear()
        assertNull(store.load())
    }
}
