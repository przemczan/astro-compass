package com.astrocompass.platesolve

import kotlin.math.sqrt

/**
 * Finds star-like blobs in a plain luminance buffer: threshold above the background noise level,
 * group thresholded pixels into 4-connected blobs, then reduce each blob to a sub-pixel,
 * intensity-weighted centroid. Deliberately works on a raw `FloatArray` (no `java.nio`, no
 * bitmap type) so it stays platform-free like the rest of `astro`/`platesolve` and is directly
 * unit-testable on the JVM with synthetic data.
 */
object CentroidDetector {

    /**
     * @param luminance row-major brightness values, size == [width] * [height].
     * @param thresholdSigma how many standard deviations above the mean a pixel must be to count
     *   as part of a star -- 5 sigma is a conservative bar against background/read noise.
     * @param minBlobPixels blobs smaller than this are treated as noise (hot pixels, cosmic rays)
     *   rather than real, resolved stars.
     * @param maxStars caps the returned list to the brightest [maxStars] blobs, for cost control
     *   on a dense/noisy frame.
     * @return centroids in the same continuous (column, row) pixel-coordinate convention as
     *   [CameraIntrinsics]' principal point, brightest first.
     */
    fun detect(
        luminance: FloatArray,
        width: Int,
        height: Int,
        thresholdSigma: Double = 5.0,
        minBlobPixels: Int = 2,
        maxStars: Int = 200,
    ): List<StarCentroid> {
        require(luminance.size == width * height) { "luminance size must be width * height" }
        if (luminance.isEmpty()) return emptyList()

        val mean = luminance.sumOf { it.toDouble() } / luminance.size
        val variance = luminance.sumOf { val d = it - mean; d * d } / luminance.size
        val threshold = mean + thresholdSigma * sqrt(variance)

        val visited = BooleanArray(luminance.size)
        val stars = mutableListOf<StarCentroid>()
        val stack = ArrayDeque<Int>()

        for (startIndex in luminance.indices) {
            if (visited[startIndex] || luminance[startIndex] <= threshold) continue

            stack.addLast(startIndex)
            visited[startIndex] = true
            var pixelCount = 0
            var sumWeight = 0.0
            var sumWeightedX = 0.0
            var sumWeightedY = 0.0

            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                val col = index % width
                val row = index / width
                val weight = luminance[index] - mean
                pixelCount++
                sumWeight += weight
                sumWeightedX += weight * col
                sumWeightedY += weight * row

                for ((dx, dy) in NEIGHBOR_OFFSETS) {
                    val neighborCol = col + dx
                    val neighborRow = row + dy
                    if (neighborCol !in 0 until width || neighborRow !in 0 until height) continue
                    val neighborIndex = neighborRow * width + neighborCol
                    if (visited[neighborIndex] || luminance[neighborIndex] <= threshold) continue
                    visited[neighborIndex] = true
                    stack.addLast(neighborIndex)
                }
            }

            if (pixelCount >= minBlobPixels && sumWeight > 0.0) {
                stars += StarCentroid(sumWeightedX / sumWeight, sumWeightedY / sumWeight, sumWeight)
            }
        }

        return stars.sortedByDescending { it.brightness }.take(maxStars)
    }

    private val NEIGHBOR_OFFSETS = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
}
