package com.ontimequant.forecast

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * xoshiro256** — a small, fast, high-quality PRNG.
 *
 * We implement it rather than using [java.util.Random] so that the engine stays pure
 * Kotlin, is byte-for-byte reproducible across platforms for a given seed, and is fast
 * enough to run tens of thousands of Monte Carlo draws off the main thread.
 *
 * Determinism matters twice over:
 *  - unit tests pin a seed and assert exact behaviour;
 *  - the solver uses *common random numbers* across candidate departure times, so the
 *    departure curve is smooth and comparisons between candidates are not polluted by
 *    simulation noise (see [DepartureSolver]).
 */
class Xoshiro256(seed: Long) {

    private var s0: Long
    private var s1: Long
    private var s2: Long
    private var s3: Long

    private var hasSpareGaussian = false
    private var spareGaussian = 0.0

    init {
        // SplitMix64 seeding, the reference initialiser for xoshiro.
        var z = seed
        fun next(): Long {
            z += -0x61c8864680b583ebL
            var x = z
            x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
            x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
            return x xor (x ushr 31)
        }
        s0 = next(); s1 = next(); s2 = next(); s3 = next()
        if (s0 == 0L && s1 == 0L && s2 == 0L && s3 == 0L) s0 = 1L
    }

    private fun rotl(x: Long, k: Int): Long = (x shl k) or (x ushr (64 - k))

    fun nextLong(): Long {
        val result = rotl(s1 * 5, 7) * 9
        val t = s1 shl 17
        s2 = s2 xor s0
        s3 = s3 xor s1
        s1 = s1 xor s2
        s0 = s0 xor s3
        s2 = s2 xor t
        s3 = rotl(s3, 45)
        return result
    }

    /** Uniform on `[0, 1)`, using the top 53 bits. */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * (1.0 / (1L shl 53).toDouble())

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return (nextDouble() * bound).toInt().coerceAtMost(bound - 1)
    }

    /** Standard normal via polar-free Box–Muller with a cached spare. */
    fun nextGaussian(): Double {
        if (hasSpareGaussian) {
            hasSpareGaussian = false
            return spareGaussian
        }
        var u1 = nextDouble()
        if (u1 < 1e-12) u1 = 1e-12
        val u2 = nextDouble()
        val r = sqrt(-2.0 * ln(u1))
        val theta = 2.0 * Math.PI * u2
        spareGaussian = r * sin(theta)
        hasSpareGaussian = true
        return r * cos(theta)
    }

    /** Exponential with the given mean. */
    fun nextExponential(mean: Double): Double {
        var u = nextDouble()
        if (u < 1e-12) u = 1e-12
        return -mean * ln(u)
    }

    /** Log-normal parameterised by the *median* and the log-scale sigma. */
    fun nextLogNormal(median: Double, sigma: Double): Double {
        if (median <= 0.0) return 0.0
        if (sigma <= 0.0) return median
        return median * exp(sigma * nextGaussian())
    }

    fun nextBoolean(p: Double): Boolean = nextDouble() < p
}
