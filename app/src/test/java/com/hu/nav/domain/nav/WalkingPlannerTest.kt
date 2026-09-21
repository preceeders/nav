package com.hu.nav.domain.nav

import com.hu.nav.domain.model.FacilityType
import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.NaviTick
import com.hu.nav.domain.model.NavigationConfig
import com.hu.nav.domain.model.RouteGuide
import com.hu.nav.domain.model.WalkLink
import com.hu.nav.domain.model.WalkPath
import com.hu.nav.domain.model.WalkStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkingPlannerTest {
    private var now = 0L
    private val logs = mutableListOf<String>()

    private fun planner(config: NavigationConfig = NavigationConfig(compassCalibrationEnabled = false)): WalkingPlanner {
        return WalkingPlanner(config, logger = { logs += it }, clock = { now })
    }

    private fun path(): WalkPath {
        val start = GeoPoint(31.2304, 121.4737)
        val mid = GeoPoint(31.2310, 121.4737)
        val end = GeoPoint(31.2320, 121.4737)
        val turn = WalkStep(
            instruction = "左转",
            orientation = "",
            iconType = 2,
            lengthMeters = 40,
            links = listOf(WalkLink(listOf(start, mid), 40, "南京东路")),
        )
        val cross = WalkStep(
            instruction = "过马路进入河南中路",
            orientation = "",
            iconType = 10,
            lengthMeters = 20,
            links = listOf(
                WalkLink(listOf(mid, end), 20, "人行横道", FacilityType.Crosswalk),
            ),
        )
        return WalkPath(1, 60, 80, listOf(turn, cross), listOf(start, mid, end))
    }

    private fun tick(
        remain: Int,
        stepRemain: Int,
        step: Int = 0,
        loc: GeoPoint = GeoPoint(31.2304, 121.4737),
        straight: Double = remain.toDouble(),
    ) = NaviTick(
        location = loc,
        bearing = 0f,
        accuracy = 5f,
        curStep = step,
        curLink = 0,
        curStepRemainMeters = stepRemain,
        routeRemainMeters = remain,
        routeRemainSeconds = remain,
        currentRoadName = "南京东路",
        nextRoadName = "河南中路",
        iconType = 2,
        straightToDestMeters = straight,
    )

    @Test
    fun startWithoutCalibrationGoesGuiding() {
        val p = planner()
        val events = p.start(path(), "人民广场")
        assertEquals(WalkingPhase.Guiding, p.phase)
        assertTrue(events.any { it is GuideEvent.CalibrationComplete })
    }

    @Test
    fun approachingTurnThenArrive() {
        val p = planner()
        p.start(path(), "人民广场")
        val approach = p.onTick(tick(remain = 50, stepRemain = 25))
        assertTrue(approach.any { it is GuideEvent.ApproachingTurn || it is GuideEvent.TurnEntered })
        val arrived = p.onTick(tick(remain = 5, stepRemain = 5, loc = GeoPoint(31.2320, 121.4737), straight = 5.0))
        assertTrue(arrived.any { it is GuideEvent.Arrived })
        assertEquals(WalkingPhase.Arrived, p.phase)
    }

    @Test
    fun sdkArrivedDoesNotFinishUntilDistanceConfirmed() {
        val p = planner()
        p.start(path(), "人民广场")
        p.onSdkArrived()
        assertEquals(WalkingPhase.Guiding, p.phase)
        val stillGoing = p.onTick(tick(remain = 80, stepRemain = 40, straight = 80.0))
        assertTrue(stillGoing.none { it is GuideEvent.Arrived })
    }

    @Test
    fun naviSpeaksRouteDetailGuidesInOrder() {
        val p = planner()
        val start = GeoPoint(31.2304, 121.4737)
        val mid = GeoPoint(31.2310, 121.4737)
        val end = GeoPoint(31.2320, 121.4737)
        val dummy = WalkStep(
            instruction = "sdk raw",
            orientation = "",
            iconType = 10,
            lengthMeters = 280,
            links = listOf(WalkLink(listOf(start, end), 280, "解放路")),
        )
        val detailPath = WalkPath(
            id = 1,
            distanceMeters = 280,
            durationSeconds = 280,
            steps = listOf(dummy),
            polyline = listOf(start, mid, end),
            guides = listOf(
                RouteGuide("从「我的位置」出发", isStart = true),
                RouteGuide(
                    instruction = "向东北步行80米过马路",
                    action = "过马路",
                    lengthMeters = 80,
                    facility = FacilityType.Crosswalk,
                ),
                RouteGuide(
                    instruction = "过马路后左转进入解放路",
                    action = "左转",
                    roadName = "解放路",
                    lengthMeters = 80,
                ),
                RouteGuide(
                    instruction = "过1个红绿灯后直行",
                    action = "直行",
                    lengthMeters = 120,
                    trafficLights = 1,
                ),
            ),
        )
        val startEvents = p.start(detailPath, "目的地")
        assertTrue(
            startEvents.any { it is GuideEvent.TurnEntered && it.instruction.contains("过马路") },
        )
        assertTrue(startEvents.none { it is GuideEvent.TurnEntered && it.instruction.contains("sdk raw") })

        p.onTick(tick(remain = 280, stepRemain = 280))

        val nextEvents = p.onTick(tick(remain = 205, stepRemain = 45))
        assertTrue(
            nextEvents.any {
                it is GuideEvent.ApproachingTurn && it.instruction.contains("左转进入解放路")
            } || nextEvents.any {
                it is GuideEvent.TurnEntered && it.instruction.contains("左转进入解放路")
            },
        )

        val lightEvents = p.onTick(tick(remain = 90, stepRemain = 90))
        assertTrue(
            lightEvents.any { event ->
                val text = when (event) {
                    is GuideEvent.TurnEntered -> event.instruction
                    is GuideEvent.ApproachingTurn -> event.instruction
                    else -> ""
                }
                text.contains("红绿灯")
            },
        )
    }

    @Test
    fun offRouteWhenFarFromPolyline() {
        val p = planner()
        p.start(path(), "人民广场")
        val far = GeoPoint(31.2304, 121.4900)
        p.onTick(tick(remain = 80, stepRemain = 40, loc = far, straight = 200.0))
        now = 3_000
        val events = p.onTick(tick(remain = 80, stepRemain = 40, loc = far, straight = 200.0))
        assertTrue(events.any { it is GuideEvent.OffRoute })
        assertTrue(events.any { it is GuideEvent.Reroute })
    }

    @Test
    fun headingOver90DegreesRemindsWithoutReroute() {
        val p = planner()
        p.start(path(), "人民广场")
        val sample = tick(remain = 55, stepRemain = 40)
        p.onCompass(180.0, sample)
        now = 800
        val events = p.onCompass(180.0, sample)
        val remind = events.filterIsInstance<GuideEvent.HeadingDeviation>().single()
        assertTrue(remind.angleDiff >= 90.0)
        assertTrue(events.none { it is GuideEvent.OffRoute || it is GuideEvent.Reroute })
        assertEquals(WalkingPhase.Guiding, p.phase)
    }

    @Test
    fun headingUnder90DegreesDoesNotRemind() {
        val p = planner()
        p.start(path(), "人民广场")
        val sample = tick(remain = 55, stepRemain = 40)
        p.onCompass(45.0, sample)
        now = 5_000
        val events = p.onCompass(45.0, sample)
        assertTrue(events.none { it is GuideEvent.HeadingDeviation })
        assertEquals(WalkingPhase.Guiding, p.phase)
    }
}
