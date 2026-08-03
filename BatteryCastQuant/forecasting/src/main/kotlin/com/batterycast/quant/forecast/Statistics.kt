package com.batterycast.quant.forecast

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal fun List<Double>.median(): Double {
    if (isEmpty()) return Double.NaN
    val s = sorted()
    val m = s.size / 2
    return if (s.size % 2 == 0) (s[m - 1] + s[m]) / 2.0 else s[m]
}

internal fun List<Double>.quantile(q: Double): Double {
    if (isEmpty()) return Double.NaN
    val s = sorted()
    val pos = q.coerceIn(0.0, 1.0) * (s.size - 1)
    val lo = floor(pos).toInt()
    val hi = min(lo + 1, s.lastIndex)
    val f = pos - lo
    return s[lo] * (1 - f) + s[hi] * f
}

internal fun mad(values: List<Double>): Double {
    if (values.isEmpty()) return Double.NaN
    val med = values.median()
    return values.map { abs(it - med) }.median()
}

internal fun theilSenSlope(points: List<Pair<Double, Double>>): Double? {
    if (points.size < 2) return null
    val slopes = ArrayList<Double>()
    for (i in 0 until points.lastIndex) {
        for (j in i + 1 until points.size) {
            val dx = points[j].first - points[i].first
            if (abs(dx) > 1e-9) slopes += (points[j].second - points[i].second) / dx
        }
    }
    return slopes.takeIf { it.isNotEmpty() }?.median()
}

internal fun ewma(values: List<Double>, alpha: Double): Double? {
    if (values.isEmpty()) return null
    var v = values.first()
    for (i in 1 until values.size) v = alpha * values[i] + (1 - alpha) * v
    return v
}

internal fun clampBattery(v: Double) = v.coerceIn(0.0, 100.0)

internal class GaussianRandom(private val random: kotlin.random.Random) {
    private var spare: Double? = null
    fun next(): Double {
        spare?.let { spare = null; return it }
        var u: Double
        var v: Double
        var s: Double
        do {
            u = random.nextDouble() * 2 - 1
            v = random.nextDouble() * 2 - 1
            s = u * u + v * v
        } while (s <= 0.0 || s >= 1.0)
        val m = sqrt(-2.0 * kotlin.math.ln(s) / s)
        spare = v * m
        return u * m
    }
}

internal fun DoubleArray.quantile(q: Double): Double = this.toList().quantile(q)
