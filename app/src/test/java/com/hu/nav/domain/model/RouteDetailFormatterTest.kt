package com.hu.nav.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDetailFormatterTest {
    @Test
    fun searchStepsMatchReferenceStyle() {
        val steps = listOf(
            SearchWalkStep("向东北沿内部道路行走63米，左转", "东北", "内部道路", 63, "左转", "", 0),
            SearchWalkStep("向西北沿内部道路行走89米，右转", "西北", "内部道路", 89, "右转", "", 0),
            SearchWalkStep("向东北沿内部道路行走36米，进入富闲路", "东北", "内部道路", 36, "进入富闲路", "", 0),
            SearchWalkStep("向西北沿富闲路行走109米，过马路", "西北", "富闲路", 109, "", "过马路", 1),
            SearchWalkStep("向西北沿富闲路行走379米，过1个红绿灯后左", "西北", "富闲路", 379, "左转", "", 0, 1),
            SearchWalkStep("向西南沿富闲路行走120米，过马路", "西南", "富闲路", 120, "", "过马路", 1),
            SearchWalkStep("向南沿银湖路行走80米，过马路", "南", "银湖路", 80, "", "过马路", 1),
            SearchWalkStep("向东沿道路行走40米，到达目的地", "东", "", 40, "到达", "", 0),
        )
        val guides = RouteDetailFormatter.fromSearchSteps(steps, "杭州业晟房地产开发有限公司")
        assertEquals("从「我的位置（杭州业晟房地产开发有限公司）」出发", guides.first().instruction)
        assertTrue(guides.any { it.instruction.contains("过马路") })
        assertTrue(guides.any { it.instruction.contains("红绿灯") })
        assertEquals(3, RouteDetailFormatter.turnCount(guides))
        assertEquals(1, RouteDetailFormatter.trafficLightCount(guides))
        assertEquals(3, RouteDetailFormatter.crossStreetCount(guides))
    }

    @Test
    fun mergeTinyNaviTurnsIntoCrossingGuides() {
        val start = GeoPoint(30.0, 120.0)
        val mid = GeoPoint(30.001, 120.0)
        val end = GeoPoint(30.002, 120.0)
        val steps = listOf(
            WalkStep("直行", "北", 10, 40, listOf(WalkLink(listOf(start, mid), 40, "富闲路")), action = "直行"),
            WalkStep(
                "过街",
                "北",
                10,
                20,
                listOf(WalkLink(listOf(mid, end), 20, "人行横道", FacilityType.Crosswalk)),
                action = "直行",
            ),
            WalkStep("左转", "西", 2, 80, listOf(WalkLink(listOf(end, GeoPoint(30.003, 120.0)), 80, "银湖路")), action = "左转"),
        )
        val guides = RouteDetailFormatter.fromNaviLinks(steps)
        assertTrue(
            "guides=${guides.map { it.instruction }}",
            guides.any { it.instruction.contains("过马路") },
        )
        assertTrue(guides.any { it.instruction.contains("左转") })
        assertEquals(1, RouteDetailFormatter.crossStreetCount(guides))
        assertEquals(1, RouteDetailFormatter.turnCount(guides))
    }

    @Test
    fun searchAssistantActionFillsMissingCrossText() {
        val steps = listOf(
            SearchWalkStep("向西北沿富闲路行走109米", "西北", "富闲路", 109, "直行", "过马路", 1),
            SearchWalkStep("向西北沿富闲路行走379米，右转", "西北", "富闲路", 379, "右转", "", 0),
        )
        val guides = RouteDetailFormatter.fromSearchSteps(steps, "杭州业晟房地产开发有限公司")
        assertTrue(guides.any { it.instruction.contains("过马路") })
        assertEquals(1, RouteDetailFormatter.crossStreetCount(guides))
    }

    @Test
    fun naviLinksProduceCrossingAndLights() {
        val a = GeoPoint(30.0, 120.0)
        val b = GeoPoint(30.001, 120.0)
        val c = GeoPoint(30.0015, 120.0)
        val d = GeoPoint(30.003, 120.0)
        val steps = listOf(
            WalkStep(
                instruction = "直行",
                orientation = "北",
                iconType = 10,
                lengthMeters = 120,
                links = listOf(
                    WalkLink(listOf(a, b), 100, "富闲路"),
                    WalkLink(listOf(b, c), 20, "", FacilityType.Crosswalk),
                ),
                action = "直行",
            ),
            WalkStep(
                instruction = "左转",
                orientation = "西",
                iconType = 2,
                lengthMeters = 80,
                links = listOf(
                    WalkLink(listOf(c, d), 80, "银湖路", hasTrafficLight = true),
                ),
                action = "左转",
            ),
        )
        val guides = RouteDetailFormatter.fromNaviLinks(steps)
        assertTrue(
            "guides=${guides.map { it.instruction }}",
            guides.any { it.instruction.contains("过马路") },
        )
        assertTrue(
            "guides=${guides.map { it.instruction }}",
            guides.any { it.instruction.contains("红绿灯") },
        )
        assertEquals(1, RouteDetailFormatter.crossStreetCount(guides))
        assertEquals(1, RouteDetailFormatter.trafficLightCount(guides))
    }

    @Test
    fun duplicateLightsOnNearbyLinksCountOnce() {
        val a = GeoPoint(30.0, 120.0)
        val b = GeoPoint(30.001, 120.0)
        val c = GeoPoint(30.002, 120.0)
        val d = GeoPoint(30.003, 120.0)
        val lit = WalkLink(listOf(a, b), 40, "富闲路", hasTrafficLight = true)
        val alsoLit = WalkLink(listOf(b, c), 40, "富闲路", hasTrafficLight = true)
        val turn = WalkLink(listOf(c, d), 30, "银湖路")
        val steps = listOf(
            WalkStep("直行", "北", 10, 40, listOf(lit), action = "直行", trafficLightCount = 2),
            WalkStep("直行", "北", 10, 40, listOf(alsoLit), action = "直行", trafficLightCount = 2),
            WalkStep("左转", "西", 2, 30, listOf(turn), action = "左转", trafficLightCount = 2),
        )
        val guides = RouteDetailFormatter.fromNaviLinks(steps)
        assertEquals("guides=${guides.map { it.instruction }}", 2, RouteDetailFormatter.trafficLightCount(guides))
        assertEquals(1, guides.count { it.instruction.contains("红绿灯") })
    }

    @Test
    fun noisyStepLightCountIsIgnoredWithoutLinkFlag() {
        val a = GeoPoint(30.0, 120.0)
        val b = GeoPoint(30.001, 120.0)
        val steps = listOf(
            WalkStep("左转", "北", 2, 40, listOf(WalkLink(listOf(a, b), 40, "富闲路")), action = "左转", trafficLightCount = 2),
            WalkStep("右转", "东", 3, 40, listOf(WalkLink(listOf(b, GeoPoint(30.002, 120.0)), 40, "富闲路")), action = "右转", trafficLightCount = 2),
        )
        val guides = RouteDetailFormatter.fromNaviLinks(steps)
        assertEquals("guides=${guides.map { it.instruction }}", 0, RouteDetailFormatter.trafficLightCount(guides))
        assertTrue(guides.none { it.instruction.contains("红绿灯") })
    }

    @Test
    fun detectShortUnnamedGapAsCrosswalk() {
        val a = GeoPoint(30.0, 120.0)
        val b = GeoPoint(30.001, 120.0)
        val c = GeoPoint(30.0012, 120.0)
        val d = GeoPoint(30.003, 120.0)
        val steps = listOf(
            WalkStep("直行", "北", 10, 100, listOf(WalkLink(listOf(a, b), 100, "富闲路")), action = "直行"),
            WalkStep("直行", "北", 10, 18, listOf(WalkLink(listOf(b, c), 18, "")), action = "直行"),
            WalkStep("左转", "西", 2, 80, listOf(WalkLink(listOf(c, d), 80, "银湖路")), action = "左转"),
        )
        val marked = RouteDetailFormatter.detectCrosswalks(steps)
        assertEquals(FacilityType.Crosswalk, marked[1].facility)
        val guides = RouteDetailFormatter.fromNaviLinks(marked)
        assertTrue("guides=${guides.map { it.instruction }}", guides.any { it.instruction.contains("过马路") })
    }

    @Test
    fun enrichSearchWithNaviCrossingAndLights() {
        val a = GeoPoint(30.0, 120.0)
        val b = GeoPoint(30.002, 120.0)
        val c = GeoPoint(30.0022, 120.0)
        val search = listOf(
            SearchWalkStep("向北沿富闲路行走220米", "北", "富闲路", 220, "直行", "", 0, polyline = listOf(a, b)),
            SearchWalkStep("向西沿银湖路行走80米，左转", "西", "银湖路", 80, "左转", "", 0, polyline = listOf(c, GeoPoint(30.0022, 119.999))),
        )
        val enriched = RouteDetailFormatter.enrichSearchSteps(
            search = search,
            crossingPoints = listOf(b),
            lightPoints = listOf(c),
            sdkLightCount = 1,
        )
        val guides = RouteDetailFormatter.fromSearchSteps(enriched, "杭州业晟房地产开发有限公司")
        assertTrue("guides=${guides.map { it.instruction }}", guides.any { it.instruction.contains("过马路") })
        assertTrue("guides=${guides.map { it.instruction }}", guides.any { it.instruction.contains("红绿灯") })
        assertEquals(1, RouteDetailFormatter.crossStreetCount(guides))
        assertEquals(1, RouteDetailFormatter.trafficLightCount(guides))
    }

    @Test
    fun twoDistantLightsSplitIntoSeparateSteps() {
        val start = GeoPoint(30.0, 120.0)
        val mid = GeoPoint(30.002, 120.0)
        val end = GeoPoint(30.0045, 120.0)
        val step = SearchWalkStep(
            instruction = "步行498米左转",
            orientation = "北",
            road = "富闲路",
            distanceMeters = 498,
            action = "左转",
            assistantAction = "",
            roadType = 0,
            polyline = listOf(start, mid, end),
        )
        val pieces = RouteDetailFormatter.splitSearchStep(
            step = step,
            lights = listOf(mid, end),
            crossings = emptyList(),
            isLast = false,
        )
        assertTrue("pieces=${pieces.map { it.trafficLights to it.action }}", pieces.size >= 2)
        assertEquals(1, pieces.maxOf { it.trafficLights })
        assertEquals(2, pieces.count { it.trafficLights == 1 })
        assertTrue(pieces.last().action.contains("左转"))
        val guides = RouteDetailFormatter.fromSearchSteps(pieces, "")
        assertEquals(2, RouteDetailFormatter.trafficLightCount(guides))
        assertEquals(2, guides.count { it.instruction.contains("红绿灯") })
        assertTrue(guides.none { it.instruction.contains("过2个红绿灯") || it.instruction.contains("过两个红绿灯") })
    }

    @Test
    fun namedRoadTurnWithoutLightInfersCrosswalk() {
        val step = SearchWalkStep(
            instruction = "沿银湖路向西南步行319米右转",
            orientation = "西南",
            road = "银湖路",
            distanceMeters = 319,
            action = "右转",
            assistantAction = "",
            roadType = 0,
        )
        val pieces = RouteDetailFormatter.splitSearchStep(step, emptyList(), emptyList(), false)
        val guides = RouteDetailFormatter.fromSearchSteps(pieces, "")
        assertTrue("guides=${guides.map { it.instruction }}", guides.any { it.instruction.contains("过马路") })
    }
}
