package com.astrocompass.platesolve

import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CentroidDetectorTest {

    private val width = 100
    private val height = 100

    private fun renderStar(buffer: FloatArray, centerX: Double, centerY: Double, peak: Double, sigma: Double) {
        val radius = (sigma * 4).toInt()
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val x = centerX.toInt() + dx
                val y = centerY.toInt() + dy
                if (x !in 0 until width || y !in 0 until height) continue
                val distanceSquared = (x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)
                buffer[y * width + x] += (peak * exp(-distanceSquared / (2 * sigma * sigma))).toFloat()
            }
        }
    }

    private fun noisyBackground(random: Random, level: Float = 20f, amplitude: Float = 2f): FloatArray =
        FloatArray(width * height) { level + random.nextDouble(-amplitude.toDouble(), amplitude.toDouble()).toFloat() }

    @Test
    fun detectsWellSeparatedStars_atTheirTrueCentroid() {
        val random = Random(42)
        val buffer = noisyBackground(random)
        val trueStars = listOf(
            Triple(20.0, 30.0, 200.0),
            Triple(70.0, 65.0, 150.0),
            Triple(45.0, 80.0, 300.0),
        )
        for ((x, y, peak) in trueStars) renderStar(buffer, x, y, peak, sigma = 1.2)

        val detected = CentroidDetector.detect(buffer, width, height)

        assertTrue(detected.size >= trueStars.size, "Expected to detect all ${trueStars.size} stars, found ${detected.size}")
        for ((trueX, trueY, _) in trueStars) {
            val nearest = detected.minBy { (it.pixelX - trueX) * (it.pixelX - trueX) + (it.pixelY - trueY) * (it.pixelY - trueY) }
            val distance = kotlin.math.sqrt((nearest.pixelX - trueX).let { it * it } + (nearest.pixelY - trueY).let { it * it })
            assertTrue(distance < 0.5, "Star at ($trueX, $trueY) recovered ${distance}px off at (${nearest.pixelX}, ${nearest.pixelY})")
        }
    }

    @Test
    fun brighterStarsAreOrderedFirst() {
        val random = Random(7)
        val buffer = noisyBackground(random)
        renderStar(buffer, 20.0, 20.0, peak = 80.0, sigma = 1.0)
        renderStar(buffer, 80.0, 80.0, peak = 300.0, sigma = 1.0)

        val detected = CentroidDetector.detect(buffer, width, height)

        assertEquals(2, detected.size)
        assertTrue(detected[0].brightness > detected[1].brightness)
        assertTrue((detected[0].pixelX - 80.0) * (detected[0].pixelX - 80.0) + (detected[0].pixelY - 80.0) * (detected[0].pixelY - 80.0) < 1.0)
    }

    @Test
    fun emptyField_detectsNothing() {
        val buffer = noisyBackground(Random(99))
        assertTrue(CentroidDetector.detect(buffer, width, height).isEmpty())
    }

    @Test
    fun tinyHotPixel_isRejectedAsNoise() {
        val random = Random(5)
        val buffer = noisyBackground(random)
        // A single saturated pixel with no neighbors above threshold -- a sensor hot pixel, not a
        // resolved star.
        buffer[50 * width + 50] = 500f

        val detected = CentroidDetector.detect(buffer, width, height, minBlobPixels = 3)

        assertTrue(detected.isEmpty(), "Expected the isolated hot pixel to be filtered out, got $detected")
    }
}
