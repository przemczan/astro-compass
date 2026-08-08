package com.astrocompass.astro

/**
 * Davenport's q-method (Markley & Crassidis, *Fundamentals of Spacecraft Attitude Determination
 * and Control*): the rotation that best maps each [measured] direction onto its paired
 * [reference] direction, in a least-squares sense -- build B = sum(measured_i outer reference_i),
 * then the 4x4 matrix K whose largest-eigenvalue eigenvector is the optimal rotation quaternion.
 * Used over Kabsch/SVD deliberately: it cannot return a reflection, so there is no
 * determinant-sign correction to get wrong.
 *
 * Shared core behind both [com.astrocompass.alignment.AlignmentSolver] (star-sync alignment) and
 * [com.astrocompass.platesolve.PlateSolver] (matched-star plate solving) -- the same "best
 * rotation from paired directions" problem in two different contexts.
 */
object AttitudeFit {

    fun solve(measured: List<Vector3>, reference: List<Vector3>): Quaternion {
        require(measured.size == reference.size) { "measured and reference must be the same size" }
        require(measured.isNotEmpty()) { "At least one direction pair is required" }

        var b = Matrix3(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        for (i in measured.indices) {
            b += measured[i] outer reference[i]
        }

        val s = b + b.transposed()
        val sigma = b.trace
        val z = Vector3(b.m12 - b.m21, b.m20 - b.m02, b.m01 - b.m10)

        val k = arrayOf(
            doubleArrayOf(s.m00 - sigma, s.m01, s.m02, z.x),
            doubleArrayOf(s.m01, s.m11 - sigma, s.m12, z.y),
            doubleArrayOf(s.m02, s.m12, s.m22 - sigma, z.z),
            doubleArrayOf(z.x, z.y, z.z, sigma),
        )

        val (eigenvalues, eigenvectors) = JacobiEigenSolver.decomposeSymmetric(k)
        val bestIndex = eigenvalues.indices.maxBy { eigenvalues[it] }
        val q1 = eigenvectors[0][bestIndex]
        val q2 = eigenvectors[1][bestIndex]
        val q3 = eigenvectors[2][bestIndex]
        val q4 = eigenvectors[3][bestIndex]
        return Quaternion(w = q4, x = q1, y = q2, z = q3).normalized()
    }
}
