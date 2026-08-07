package com.astroguider.alignment

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Classic cyclic Jacobi eigenvalue algorithm for a real symmetric matrix: repeatedly zeroes the
 * largest off-diagonal element via a Givens rotation until the matrix is (numerically) diagonal.
 * Used for Davenport's q-method, which needs the eigenvector of the largest eigenvalue of a
 * symmetric 4x4 matrix -- small and fixed-size enough that this simple, robust method beats
 * pulling in a general-purpose linear algebra library.
 */
internal object JacobiEigenSolver {

    /** @return eigenvalues, and eigenvectors as the columns of the returned matrix. */
    fun decomposeSymmetric(input: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val n = input.size
        val a = Array(n) { input[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }

        repeat(100) {
            var offDiagonalSumSquares = 0.0
            for (i in 0 until n) for (j in i + 1 until n) offDiagonalSumSquares += a[i][j] * a[i][j]
            if (offDiagonalSumSquares < 1e-24) return eigenvalues(a) to v

            for (p in 0 until n) {
                for (q in p + 1 until n) {
                    if (abs(a[p][q]) < 1e-15) continue
                    rotate(a, v, p, q)
                }
            }
        }
        return eigenvalues(a) to v
    }

    private fun eigenvalues(a: Array<DoubleArray>): DoubleArray = DoubleArray(a.size) { a[it][it] }

    private fun rotate(a: Array<DoubleArray>, v: Array<DoubleArray>, p: Int, q: Int) {
        val n = a.size
        val theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q])
        val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
        val c = 1.0 / sqrt(t * t + 1.0)
        val s = t * c

        val app = a[p][p]
        val aqq = a[q][q]
        val apq = a[p][q]
        a[p][p] = c * c * app - 2 * s * c * apq + s * s * aqq
        a[q][q] = s * s * app + 2 * s * c * apq + c * c * aqq
        a[p][q] = 0.0
        a[q][p] = 0.0

        for (i in 0 until n) {
            if (i != p && i != q) {
                val aip = a[i][p]
                val aiq = a[i][q]
                a[i][p] = c * aip - s * aiq
                a[p][i] = a[i][p]
                a[i][q] = s * aip + c * aiq
                a[q][i] = a[i][q]
            }
        }
        for (i in 0 until n) {
            val vip = v[i][p]
            val viq = v[i][q]
            v[i][p] = c * vip - s * viq
            v[i][q] = s * vip + c * viq
        }
    }
}
