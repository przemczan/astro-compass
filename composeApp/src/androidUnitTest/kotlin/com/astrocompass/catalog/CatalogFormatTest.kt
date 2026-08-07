package com.astrocompass.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [CatalogFormat] against the actual committed `stars.bin`/`dso.bin` blobs, not just
 * synthetic bytes -- these are the real bundled resources the app decodes on first launch, so a
 * field-order mismatch against [tools/build-catalogs.mjs]'s writer would otherwise go uncaught
 * until it crashed or produced garbage coordinates on a device.
 *
 * Lives in `androidUnitTest` (not `commonTest`) because it reads the blobs as plain JVM classpath
 * resources -- Compose's `Res.readBytes` needs `android.util.Log`, which a non-Robolectric unit
 * test doesn't provide.
 */
class CatalogFormatTest {

    private fun readResource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("Resource not found on test classpath: $name")
        return stream.use { it.readBytes() }
    }

    @Test
    fun decodeStars_readsTheCommittedBlob() {
        val stars = CatalogFormat.decodeStars(readResource("stars.bin"))

        assertEquals(3268, stars.size)

        val vega = stars.single { it.properName == "Vega" }
        assertEquals(91262, vega.hip)
        assertEquals("Lyr", vega.constellation)
        assertClose(18.616, vega.j2000.rightAscension.hours)
        assertClose(38.784, vega.j2000.declination.degrees)
        assertClose(0.03, vega.magnitude.toDouble())
    }

    @Test
    fun decodeDeepSkyObjects_readsTheCommittedBlob() {
        val objects = CatalogFormat.decodeDeepSkyObjects(readResource("dso.bin"))

        assertEquals(13372, objects.size)

        val andromeda = objects.single { it.catalogDesignation == "NGC0224" }
        assertEquals(31, andromeda.messier)
        assertEquals("Andromeda Galaxy", andromeda.commonName)
        assertClose(10.685, andromeda.j2000.rightAscension.degrees)
        assertClose(41.269, andromeda.j2000.declination.degrees)
        assertClose(3.44, andromeda.magnitude.toDouble())
    }

    private fun assertClose(expected: Double, actual: Double, absoluteTolerance: Double = 0.001) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= absoluteTolerance,
            "Expected $expected within $absoluteTolerance but was $actual",
        )
    }
}
