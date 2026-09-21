package com.hu.nav.domain.nav

import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.TransitKind
import com.hu.nav.domain.model.TransitSegment
import com.hu.nav.domain.model.TransitStop
import com.hu.nav.domain.model.TravelMode
import com.hu.nav.domain.model.WalkPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitPlannerTest {
    private val start = GeoPoint(30.0, 120.0)
    private val station = GeoPoint(30.001, 120.0)
    private val mid = GeoPoint(30.004, 120.0)
    private val alight = GeoPoint(30.008, 120.0)
    private val dest = GeoPoint(30.009, 120.0)

    private fun path(): WalkPath {
        val walk = TransitSegment(
            kind = TransitKind.Walk,
            instruction = "步行约120米，前往富闲路站",
            arrival = TransitStop("富闲路站", station),
            distanceMeters = 120,
            durationSeconds = 150,
            polyline = listOf(start, station),
        )
        val ride = TransitSegment(
            kind = TransitKind.Bus,
            instruction = "乘坐7路，从富闲路站上车，经过2站，在银湖路站下车",
            lineName = "7路",
            departure = TransitStop("富闲路站", station),
            arrival = TransitStop("银湖路站", alight),
            viaStops = listOf(TransitStop("中途站", mid)),
            distanceMeters = 800,
            durationSeconds = 600,
            polyline = listOf(station, mid, alight),
        )
        val lastWalk = TransitSegment(
            kind = TransitKind.Walk,
            instruction = "步行约80米，到达目的地",
            arrival = TransitStop("目的地", dest),
            distanceMeters = 80,
            durationSeconds = 100,
            polyline = listOf(alight, dest),
        )
        return WalkPath(
            id = 0,
            distanceMeters = 1000,
            durationSeconds = 850,
            steps = emptyList(),
            polyline = listOf(start, station, mid, alight, dest),
            mode = TravelMode.Transit,
            walkDistanceMeters = 200,
            transferCount = 0,
            lineSummary = "7路",
            transitSegments = listOf(walk, ride, lastWalk),
        )
    }

    @Test
    fun startSpeaksOverviewThenBoardingWalk() {
        val planner = TransitPlanner()
        val events = planner.start(path(), "银湖公园")
        assertTrue(events.first() is GuideEvent.TransitPrompt)
        assertTrue((events.first() as GuideEvent.TransitPrompt).text.contains("公交全程"))
        assertFalse(planner.riding)
        assertEquals("富闲路站", planner.currentWalkTargetName())
    }

    @Test
    fun walkArrivedThenRideThenAlight() {
        val planner = TransitPlanner()
        planner.start(path(), "银湖公园")
        val afterWalk = planner.onWalkArrived()
        assertTrue(planner.riding)
        assertTrue(afterWalk.any { it is GuideEvent.TransitPrompt && it.text.contains("7路") })

        val next = planner.onRideLocation(GeoPoint(30.0035, 120.0))
        assertTrue(next.any { it is GuideEvent.TransitPrompt && it.text.contains("下一站") })

        val nearAlight = planner.onRideLocation(GeoPoint(30.0079, 120.0))
        assertTrue(nearAlight.any { event ->
            event is GuideEvent.TransitPrompt && event.text.contains("下车") ||
                event is GuideEvent.Arrived
        })
        assertFalse(planner.riding)
    }
}
