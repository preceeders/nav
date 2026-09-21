package com.hu.nav.domain.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val lat: Double,
    val lng: Double,
) {
    fun isValid(): Boolean = lat in -90.0..90.0 && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0)
}

object GeoMath {
    private const val EARTH_RADIUS_M = 6371000.0

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** 0–360, 正北为 0，顺时针。 */
    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        val brng = Math.toDegrees(atan2(y, x))
        return (brng + 360.0) % 360.0
    }

    /** 两角夹角 0–180。 */
    fun angleDiff(a: Double, b: Double): Double {
        val d = ((a - b + 540.0) % 360.0) - 180.0
        return kotlin.math.abs(d)
    }

    /** 从 [from] 转到 [to]：正值为顺时针（右转），负值为左转，范围 -180..180。 */
    fun signedAngleDiff(from: Double, to: Double): Double {
        return ((to - from + 540.0) % 360.0) - 180.0
    }

    /** 当前位置沿折线前方 [lookaheadMeters] 的路线朝向。 */
    fun bearingAheadOnPolyline(
        point: GeoPoint,
        line: List<GeoPoint>,
        lookaheadMeters: Double = 15.0,
    ): Double? {
        if (line.size < 2) return null
        var bestIndex = 0
        var bestClosest = line[0]
        var bestDist = Double.POSITIVE_INFINITY
        for (i in 0 until line.lastIndex) {
            val closest = closestPointOnSegment(point, line[i], line[i + 1])
            val dist = distanceMeters(point, closest)
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
                bestClosest = closest
            }
        }
        var remaining = lookaheadMeters
        var from = bestClosest
        for (i in bestIndex until line.lastIndex) {
            val to = line[i + 1]
            val seg = distanceMeters(from, to)
            if (seg >= remaining.coerceAtLeast(1.0)) {
                return bearingDegrees(from, to)
            }
            remaining -= seg
            from = to
        }
        return if (distanceMeters(bestClosest, line.last()) >= 1.0) {
            bearingDegrees(bestClosest, line.last())
        } else if (line.size >= 2) {
            bearingDegrees(line[line.lastIndex - 1], line.last())
        } else {
            null
        }
    }

    private fun closestPointOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): GeoPoint {
        val abx = b.lng - a.lng
        val aby = b.lat - a.lat
        val denom = abx * abx + aby * aby
        if (denom < 1e-18) return a
        val t = ((p.lng - a.lng) * abx + (p.lat - a.lat) * aby) / denom
        val clamped = t.coerceIn(0.0, 1.0)
        return GeoPoint(lat = a.lat + clamped * aby, lng = a.lng + clamped * abx)
    }

    fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
        val t = fraction.coerceIn(0.0, 1.0)
        return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
    }

    fun polylineLength(line: List<GeoPoint>): Double {
        if (line.size < 2) return 0.0
        var sum = 0.0
        for (i in 0 until line.lastIndex) {
            sum += distanceMeters(line[i], line[i + 1])
        }
        return sum
    }

    /** 沿折线截取 [startMeters, endMeters] 一段，至少两点。 */
    fun slicePolyline(line: List<GeoPoint>, startMeters: Double, endMeters: Double): List<GeoPoint> {
        if (line.size < 2) return line
        val start = startMeters.coerceAtLeast(0.0)
        val end = endMeters.coerceAtLeast(start + 1.0)
        val out = mutableListOf<GeoPoint>()
        var acc = 0.0
        var started = false
        for (i in 0 until line.lastIndex) {
            val a = line[i]
            val b = line[i + 1]
            val seg = distanceMeters(a, b)
            val segStart = acc
            val segEnd = acc + seg
            if (segEnd < start - 0.01) {
                acc = segEnd
                continue
            }
            if (segStart > end + 0.01) break
            if (!started) {
                val t = if (seg < 1e-6) 0.0 else ((start - segStart) / seg)
                out += interpolate(a, b, t)
                started = true
            }
            if (segEnd >= end) {
                val t = if (seg < 1e-6) 1.0 else ((end - segStart) / seg)
                val p = interpolate(a, b, t)
                if (out.lastOrNull() != p) out += p
                return out
            }
            if (out.lastOrNull() != b) out += b
            acc = segEnd
        }
        if (out.size < 2) {
            return listOf(line[line.lastIndex - 1], line.last())
        }
        return out
    }

    fun appendPolyline(into: MutableList<GeoPoint>, extra: List<GeoPoint>) {
        extra.forEach { point ->
            if (into.lastOrNull() != point) into += point
        }
    }

    fun compassDirection(bearing: Double): String {
        val dirs = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val index = ((bearing + 22.5) / 45.0).toInt() % 8
        return dirs[index]
    }

    fun distanceToPolyline(point: GeoPoint, line: List<GeoPoint>): Double {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY
        if (line.size == 1) return distanceMeters(point, line.first())
        var min = Double.POSITIVE_INFINITY
        for (i in 0 until line.size - 1) {
            min = minOf(min, distanceToSegment(point, line[i], line[i + 1]))
        }
        return min
    }

    private fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val ab = distanceMeters(a, b)
        if (ab < 1e-6) return distanceMeters(p, a)
        val ap = distanceMeters(p, a)
        val bp = distanceMeters(p, b)
        // 平面近似：用经纬度线性插值足够步行尺度
        val ax = a.lng
        val ay = a.lat
        val bx = b.lng
        val by = b.lat
        val px = p.lng
        val py = p.lat
        val abx = bx - ax
        val aby = by - ay
        val t = ((px - ax) * abx + (py - ay) * aby) / (abx * abx + aby * aby)
        val clamped = t.coerceIn(0.0, 1.0)
        val closest = GeoPoint(lat = ay + clamped * aby, lng = ax + clamped * abx)
        return distanceMeters(p, closest).also { _ ->
            // 用三边兜底，避免插值在极短段失真
            if (ap + bp < ab * 1.02) return minOf(ap, bp, distanceMeters(p, closest))
        }
    }
}
