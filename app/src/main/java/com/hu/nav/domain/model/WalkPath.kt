package com.hu.nav.domain.model

enum class FacilityType {
    None,
    Crosswalk,
    Bridge,
    Tunnel,
    Lift,
    Escalator,
}

data class WalkLink(
    val polyline: List<GeoPoint>,
    val lengthMeters: Int,
    val roadName: String,
    val facility: FacilityType = FacilityType.None,
    val hasTrafficLight: Boolean = false,
) {
    val expectedBearing: Double?
        get() {
            if (polyline.size < 2) return null
            return GeoMath.bearingDegrees(polyline.first(), polyline.last())
        }
}

data class WalkStep(
    val instruction: String,
    val orientation: String,
    val iconType: Int,
    val lengthMeters: Int,
    val links: List<WalkLink>,
    val remainDistance: Int = lengthMeters,
    val action: String = "",
    val trafficLightCount: Int = 0,
) {
    val isTurn: Boolean get() = iconType in TURN_ICON_TYPES
    val roadName: String get() = links.firstOrNull { it.roadName.isNotBlank() }?.roadName.orEmpty()
    val facility: FacilityType
        get() = links.firstOrNull { it.facility != FacilityType.None }?.facility ?: FacilityType.None

    companion object {
        // 高德步行/驾车转向图标：左转、右转、斜向、掉头、环岛等，不含直行(10)
        val TURN_ICON_TYPES = setOf(2, 3, 4, 5, 6, 7, 8, 9, 14, 15)
    }
}

data class RouteGuide(
    val instruction: String,
    val action: String = "",
    val roadName: String = "",
    val lengthMeters: Int = 0,
    val trafficLights: Int = 0,
    val facility: FacilityType = FacilityType.None,
    val isStart: Boolean = false,
    val isEnd: Boolean = false,
    val polyline: List<GeoPoint> = emptyList(),
)

data class WalkPath(
    val id: Int,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val steps: List<WalkStep>,
    val polyline: List<GeoPoint>,
    val guides: List<RouteGuide> = emptyList(),
    val originAddress: String = "",
    val sdkTrafficLightCount: Int = 0,
) {
    val displayGuides: List<RouteGuide>
        get() {
            val body = guides.ifEmpty { RouteDetailFormatter.fromNaviLinks(steps) }
            val hasStart = body.any { it.isStart }
            return if (hasStart) body else listOf(RouteDetailFormatter.startGuide(originAddress)) + body
        }

    val turnCount: Int
        get() = RouteDetailFormatter.turnCount(displayGuides)

    val trafficLightCount: Int
        get() {
            val fromGuides = RouteDetailFormatter.trafficLightCount(displayGuides)
            return if (fromGuides > 0) fromGuides else sdkTrafficLightCount
        }

    val crosswalkCount: Int
        get() = RouteDetailFormatter.crossStreetCount(displayGuides)

    fun formatDistance(): String =
        if (distanceMeters >= 1000) String.format("%.1f公里", distanceMeters / 1000.0) else "${distanceMeters}米"

    fun formatDuration(): String {
        val minutes = (durationSeconds / 60).coerceAtLeast(1)
        return "预计${minutes}分钟"
    }

    fun durationLabel(): String {
        val minutes = (durationSeconds / 60).coerceAtLeast(1)
        return "${minutes}分钟"
    }

    fun viaRoadNames(): List<String> =
        displayGuides.map { it.roadName.trim() }
            .filter { it.isNotBlank() && it != "无名道路" }
            .distinct()

    fun overviewText(): String {
        val via = viaRoadNames().take(3).joinToString("，")
        val viaPart = if (via.isNotBlank()) "，途经$via" else ""
        return "全程${formatDistance()}$viaPart，${formatDuration()}"
    }

    fun cardStats(): String =
        "${formatDistance()}  转弯${turnCount}次  红绿灯${trafficLightCount}个"

    fun statsLine(): String =
        "${formatDistance()}，${formatDuration()}，转弯${turnCount}次，红绿灯${trafficLightCount}个，过马路${crosswalkCount}次"

    fun summary(index: Int): String = "方案${index + 1}，${statsLine()}"

    fun displayPolyline(): List<GeoPoint> {
        val fromSteps = steps.flatMap { step -> step.links.flatMap { it.polyline } }
        val fromGuides = guides.flatMap { it.polyline }
        return listOf(polyline, fromSteps, fromGuides).maxBy { it.size }.let { densest ->
            if (densest.size >= 2) densest else polyline.ifEmpty { fromSteps.ifEmpty { fromGuides } }
        }
    }

    fun guideFocusPolyline(index: Int): List<GeoPoint> {
        val guides = displayGuides
        val guide = guides.getOrNull(index) ?: return emptyList()
        if (guide.polyline.size >= 2) return guide.polyline
        val line = displayPolyline()
        if (line.size < 2) return emptyList()
        if (guide.isStart) {
            return GeoMath.slicePolyline(line, 0.0, 20.0)
        }
        var start = 0.0
        for (i in 0 until index) {
            val prev = guides[i]
            if (prev.isStart) continue
            start += prev.lengthMeters.coerceAtLeast(0)
        }
        val span = guide.lengthMeters.coerceAtLeast(15).toDouble()
        return GeoMath.slicePolyline(line, start, start + span)
    }

    fun spokenGuides(): List<RouteGuide> =
        displayGuides.filter { !it.isStart && !it.isEnd && !it.instruction.contains("到达") }

    fun guideIndexForRemain(remainMeters: Int): Int {
        val guides = spokenGuides()
        if (guides.isEmpty()) return -1
        val total = distanceMeters.coerceAtLeast(guides.sumOf { it.lengthMeters.coerceAtLeast(1) })
        val traveled = (total - remainMeters).coerceAtLeast(0)
        var acc = 0
        guides.forEachIndexed { index, guide ->
            acc += guide.lengthMeters.coerceAtLeast(1)
            if (traveled < acc) return index
        }
        return guides.lastIndex
    }

    fun remainInGuide(remainMeters: Int): Int {
        val guides = spokenGuides()
        val index = guideIndexForRemain(remainMeters)
        if (index < 0) return remainMeters
        val following = guides.drop(index + 1).sumOf { it.lengthMeters }
        return (remainMeters - following).coerceAtLeast(0)
    }

    fun linkAt(stepIndex: Int, linkIndex: Int): WalkLink? {
        return steps.getOrNull(stepIndex)?.links?.getOrNull(linkIndex)
    }

    fun expectedBearingAt(stepIndex: Int, linkIndex: Int, location: GeoPoint? = null): Double? {
        if (location != null && polyline.size >= 2) {
            GeoMath.bearingAheadOnPolyline(location, polyline)?.let { return it }
        }
        val link = linkAt(stepIndex, linkIndex)
        if (location != null && link != null && link.polyline.size >= 2) {
            GeoMath.bearingAheadOnPolyline(location, link.polyline)?.let { return it }
        }
        return link?.expectedBearing ?: polyline.let {
            if (it.size < 2) null else GeoMath.bearingDegrees(it[0], it[1])
        }
    }
}
