package com.hu.nav.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitCopyTest {
    @Test
    fun shortLineNameDropsDirection() {
        assertEquals("地铁2号线", TransitCopy.shortLineName("地铁2号线(朝阳公园--良乡南关)"))
        assertEquals("7路", TransitCopy.shortLineName("7路(火车站--大学城)"))
    }

    @Test
    fun rideInstructionIncludesStops() {
        val text = TransitCopy.rideInstruction(
            kind = TransitKind.Bus,
            lineName = "7路",
            departure = "富闲路",
            arrival = "银湖路",
            passStationCount = 3,
        )
        assertTrue(text.contains("乘坐7路"))
        assertTrue(text.contains("富闲路"))
        assertTrue(text.contains("银湖路"))
        assertTrue(text.contains("经过3站"))
    }

    @Test
    fun transitCardStats() {
        val path = WalkPath(
            id = 0,
            distanceMeters = 5200,
            durationSeconds = 32 * 60,
            steps = emptyList(),
            polyline = emptyList(),
            mode = TravelMode.Transit,
            costYuan = 2f,
            walkDistanceMeters = 800,
            transferCount = 1,
            lineSummary = "地铁2号线 → 7路",
        )
        assertEquals("步行800米  换乘1次  2元", path.cardStats())
        assertTrue(path.overviewText().contains("公交全程"))
        assertTrue(path.overviewText().contains("地铁2号线"))
    }
}
