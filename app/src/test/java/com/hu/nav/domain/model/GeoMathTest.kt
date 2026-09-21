package com.hu.nav.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test
    fun signedAngleDiffLeftAndRight() {
        assertEquals(-90.0, GeoMath.signedAngleDiff(0.0, 270.0), 0.01)
        assertEquals(90.0, GeoMath.signedAngleDiff(0.0, 90.0), 0.01)
        assertEquals(180.0, kotlin.math.abs(GeoMath.signedAngleDiff(0.0, 180.0)), 0.01)
    }

    @Test
    fun bearingAheadUsesLocalSegment() {
        val south = GeoPoint(31.2300, 121.4737)
        val mid = GeoPoint(31.2310, 121.4737)
        val east = GeoPoint(31.2310, 121.4750)
        val heading = GeoMath.bearingAheadOnPolyline(GeoPoint(31.2302, 121.4737), listOf(south, mid, east), 15.0)
        requireNotNull(heading)
        assertTrue("heading=$heading", heading < 20.0 || heading > 340.0)
        val later = GeoMath.bearingAheadOnPolyline(GeoPoint(31.2310, 121.4738), listOf(south, mid, east), 15.0)
        requireNotNull(later)
        assertTrue("later=$later", later in 60.0..120.0)
    }

    @Test
    fun slicePolylineKeepsSegmentOrder() {
        val a = GeoPoint(31.2300, 121.4737)
        val b = GeoPoint(31.2310, 121.4737)
        val c = GeoPoint(31.2320, 121.4737)
        val sliced = GeoMath.slicePolyline(listOf(a, b, c), 0.0, GeoMath.distanceMeters(a, b) / 2)
        assertTrue(sliced.size >= 2)
        assertEquals(a.lng, sliced.first().lng, 0.00001)
    }

    @Test
    fun guideFocusPolylineFollowsSelectedSegment() {
        val a = GeoPoint(31.2300, 121.4737)
        val b = GeoPoint(31.2310, 121.4737)
        val c = GeoPoint(31.2320, 121.4737)
        val path = WalkPath(
            id = 1,
            distanceMeters = 200,
            durationSeconds = 200,
            steps = listOf(
                WalkStep("直行", "北", 10, 100, listOf(WalkLink(listOf(a, b), 100, "富闲路"))),
                WalkStep("左转", "西", 2, 100, listOf(WalkLink(listOf(b, c), 100, "银湖路")), action = "左转"),
            ),
            polyline = listOf(a, b, c),
        )
        val start = path.guideFocusPolyline(0)
        assertTrue(start.size >= 2)
        val body = path.displayGuides.indexOfFirst { it.instruction.contains("左转") }
        assertTrue(body >= 0)
        val focus = path.guideFocusPolyline(body)
        assertTrue(focus.size >= 2)
        assertTrue(GeoMath.distanceMeters(focus.last(), c) < GeoMath.distanceMeters(focus.last(), a))
    }

    @Test
    fun displayPolylinePrefersDenserGeometry() {
        val a = GeoPoint(31.2300, 121.4737)
        val b = GeoPoint(31.2305, 121.4737)
        val c = GeoPoint(31.2310, 121.4737)
        val path = WalkPath(
            id = 1,
            distanceMeters = 100,
            durationSeconds = 80,
            steps = listOf(
                WalkStep("直行", "北", 10, 100, listOf(WalkLink(listOf(a, b, c), 100, "富闲路"))),
            ),
            polyline = listOf(a, c),
        )
        val line = path.displayPolyline()
        assertEquals(3, line.size)
        assertEquals(b, line[1])
    }
}
