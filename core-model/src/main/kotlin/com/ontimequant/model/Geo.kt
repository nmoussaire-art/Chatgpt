package com.ontimequant.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS-84 coordinate. */
data class GeoPoint(val latitude: Double, val longitude: Double) {

    fun isValid(): Boolean = latitude in -90.0..90.0 && longitude in -180.0..180.0

    companion object {
        const val EARTH_RADIUS_METRES = 6_371_008.8
    }
}

/** Great-circle distance in metres between two points (haversine). */
fun GeoPoint.distanceTo(other: GeoPoint): Double {
    val lat1 = Math.toRadians(latitude)
    val lat2 = Math.toRadians(other.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(other.longitude - longitude)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * GeoPoint.EARTH_RADIUS_METRES * asin(min(1.0, sqrt(a)))
}

/**
 * Shortest distance in metres from [this] point to the poly-line described by [path].
 * Uses a local equirectangular projection which is accurate enough at city scale.
 */
fun GeoPoint.distanceToPath(path: List<GeoPoint>): Double {
    if (path.isEmpty()) return Double.POSITIVE_INFINITY
    if (path.size == 1) return distanceTo(path.first())
    var best = Double.POSITIVE_INFINITY
    for (i in 0 until path.size - 1) {
        best = min(best, distanceToSegment(path[i], path[i + 1]))
    }
    return best
}

private fun GeoPoint.distanceToSegment(a: GeoPoint, b: GeoPoint): Double {
    val latRef = Math.toRadians((a.latitude + b.latitude) / 2.0)
    val mx = GeoPoint.EARTH_RADIUS_METRES * cos(latRef) * Math.PI / 180.0
    val my = GeoPoint.EARTH_RADIUS_METRES * Math.PI / 180.0

    val px = longitude * mx
    val py = latitude * my
    val ax = a.longitude * mx
    val ay = a.latitude * my
    val bx = b.longitude * mx
    val by = b.latitude * my

    val dx = bx - ax
    val dy = by - ay
    val lenSq = dx * dx + dy * dy
    if (lenSq <= 0.0) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
    val t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / lenSq))
    val cx = ax + t * dx
    val cy = ay + t * dy
    return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
}
