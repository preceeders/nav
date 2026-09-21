package com.hu.nav.data.amap

import android.util.Log
import com.amap.api.navi.model.AMapNaviLink
import com.amap.api.navi.model.AMapNaviPath
import com.amap.api.navi.model.AMapNaviStep
import com.amap.api.navi.model.NaviLatLng
import com.amap.api.services.route.WalkPath as AmapWalkPath
import com.amap.api.services.route.WalkStep as AmapWalkStep
import com.hu.nav.domain.model.FacilityType
import com.hu.nav.domain.model.GeoMath
import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.RouteDetailFormatter
import com.hu.nav.domain.model.RouteGuide
import com.hu.nav.domain.model.SearchWalkStep
import com.hu.nav.domain.model.WalkLink
import com.hu.nav.domain.model.WalkPath
import com.hu.nav.domain.model.WalkStep

object PathMapper {
    fun toWalkPath(id: Int, path: AMapNaviPath, originAddress: String = ""): WalkPath {
        val rawSteps = path.steps.orEmpty()
        val lights = path.lightList.orEmpty().map { it.toGeo() }
        val steps = rawSteps.mapIndexed { index, step ->
            step.toWalkStep(isFirst = index == 0, isLast = index == rawSteps.lastIndex)
        }.let { RouteDetailFormatter.detectCrosswalks(it) }
            .let { markTrafficLights(it, lights, path.trafficLightCount) }
        val polyline = densestPolyline(path, steps)
        val crossLinks = steps.sumOf { step -> step.links.count { it.facility == FacilityType.Crosswalk } }
        val litLinks = steps.sumOf { step -> step.links.count { it.hasTrafficLight } }
        Log.i(
            TAG,
            "navi path id=$id length=${path.allLength} steps=${steps.size} " +
                "sdkLights=${path.trafficLightCount} lightPts=${lights.size} " +
                "crossLinks=$crossLinks litLinks=$litLinks links=" +
                steps.flatMap { step ->
                    step.links.map { link ->
                        "${link.roadName.ifBlank { "_" }}/${link.lengthMeters}m/${link.facility}/lit=${link.hasTrafficLight}"
                    }
                }.joinToString(","),
        )
        return WalkPath(
            id = id,
            distanceMeters = path.allLength,
            durationSeconds = path.allTime,
            steps = steps,
            polyline = polyline,
            guides = RouteDetailFormatter.fromNaviLinks(steps),
            originAddress = originAddress,
            sdkTrafficLightCount = path.trafficLightCount.coerceAtLeast(maxOf(lights.size, litLinks)),
        )
    }

    fun toGuides(searchPath: AmapWalkPath, naviPath: WalkPath, originAddress: String): List<RouteGuide> {
        val steps = searchPath.steps.orEmpty().map { it.toSearchStep() }
        val lightPts = naviPath.steps.flatMap { step ->
            step.links.mapNotNull { link ->
                if (link.hasTrafficLight) RouteDetailFormatter.linkMidpoint(link) else null
            }
        }
        val crossPts = naviPath.steps.flatMap { step ->
            step.links.mapNotNull { link ->
                if (link.facility == FacilityType.Crosswalk) RouteDetailFormatter.linkMidpoint(link) else null
            }
        }
        Log.i(
            TAG,
            "search walk steps=${steps.size} aux=" +
                steps.joinToString(" | ") { s ->
                    "act=${s.action} aux=${s.assistantAction} rt=${s.roadType} ${s.instruction}"
                } + " naviLights=${lightPts.size} naviCross=${crossPts.size} sdkLights=${naviPath.sdkTrafficLightCount}",
        )
        val enriched = RouteDetailFormatter.enrichSearchSteps(
            steps,
            crossPts,
            lightPts,
            naviPath.sdkTrafficLightCount,
        )
        Log.i(
            TAG,
            "enriched steps=" + enriched.joinToString(" | ") { s ->
                "${s.distanceMeters}m act=${s.action} aux=${s.assistantAction} lit=${s.trafficLights} rt=${s.roadType}"
            },
        )
        return RouteDetailFormatter.fromSearchSteps(enriched, originAddress)
    }

    fun withSearchGuides(path: WalkPath, searchPath: AmapWalkPath, originAddress: String): WalkPath {
        val searchGuides = toGuides(searchPath, path, originAddress)
        val naviGuides = path.guides.ifEmpty { RouteDetailFormatter.fromNaviLinks(path.steps) }
        val useSearch = searchGuides.any { !it.isStart }
        val guides = if (useSearch) searchGuides else naviGuides
        Log.i(
            TAG,
            "pick details use=${if (useSearch) "search+navi" else "navi"} " +
                "cross=${RouteDetailFormatter.crossStreetCount(guides)} " +
                "lights=${RouteDetailFormatter.trafficLightCount(guides)}",
        )
        return path.copy(
            guides = guides,
            originAddress = originAddress,
            distanceMeters = if (useSearch && searchPath.distance > 0) searchPath.distance.toInt() else path.distanceMeters,
            durationSeconds = if (useSearch && searchPath.duration > 0) searchPath.duration.toInt() else path.durationSeconds,
            sdkTrafficLightCount = maxOf(
                path.sdkTrafficLightCount,
                RouteDetailFormatter.trafficLightCount(guides),
            ),
        )
    }

    private fun AmapWalkStep.toSearchStep(): SearchWalkStep {
        val instruction = instruction.orEmpty()
        return SearchWalkStep(
            instruction = instruction,
            orientation = orientation.orEmpty(),
            road = road.orEmpty(),
            distanceMeters = distance.toInt(),
            action = action.orEmpty(),
            assistantAction = assistantAction.orEmpty(),
            roadType = roadType,
            trafficLights = LIGHTS_REGEX.find(instruction)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            polyline = polyline.orEmpty().map { GeoPoint(it.latitude, it.longitude) },
        )
    }

    private fun AMapNaviStep.toWalkStep(isFirst: Boolean, isLast: Boolean): WalkStep {
        val links = links.orEmpty().map { it.toWalkLink() }
        val road = links.firstOrNull { it.roadName.isNotBlank() }?.roadName.orEmpty()
        val action = if (isLast) "到达目的地" else turnText(iconType, isFirst)
        val facility = links.firstOrNull { it.facility != FacilityType.None }?.facility ?: FacilityType.None
        val lights = 0
        val orientation = polylineOrientation(coords.orEmpty().map { it.toGeo() }.ifEmpty {
            links.flatMap { it.polyline }
        })
        val instruction = RouteDetailFormatter.buildInstruction(
            orientation = orientation,
            roadName = road,
            meters = length,
            action = action,
            assistantAction = if (facility == FacilityType.Crosswalk) "过马路" else "",
            trafficLights = lights,
            facility = facility,
            isLast = isLast,
        )
        return WalkStep(
            instruction = instruction,
            orientation = orientation,
            iconType = iconType,
            lengthMeters = length,
            links = links,
            action = action,
            trafficLightCount = lights,
        )
    }

    private fun turnText(iconType: Int, isFirst: Boolean): String = when (iconType) {
        2 -> "左转"
        3 -> "右转"
        4, 5 -> "向左前方"
        6, 19 -> "向右前方"
        7 -> "向左后方"
        8 -> "向右后方"
        9 -> "掉头"
        10 -> if (isFirst) "出发" else "直行"
        11 -> "到达"
        14 -> "进入环岛"
        15 -> "驶出环岛"
        else -> if (isFirst) "出发" else ""
    }

    private fun densestPolyline(path: AMapNaviPath, steps: List<WalkStep>): List<GeoPoint> {
        val fromPath = path.coordList.orEmpty().map { it.toGeo() }
        val fromSteps = path.steps.orEmpty().flatMap { step ->
            val stepLine = step.coords.orEmpty().map { it.toGeo() }
            if (stepLine.size >= 2) {
                stepLine
            } else {
                step.links.orEmpty().flatMap { link -> link.coords.orEmpty().map { it.toGeo() } }
            }
        }
        val fromLinks = steps.flatMap { step -> step.links.flatMap { it.polyline } }
        return listOf(fromPath, fromSteps, fromLinks).maxBy { it.size }
    }

    private fun AMapNaviLink.toWalkLink(): WalkLink {
        val name = roadName.orEmpty()
        return WalkLink(
            polyline = coords.orEmpty().map { it.toGeo() },
            lengthMeters = length,
            roadName = name,
            facility = inferFacility(linkType, name),
            hasTrafficLight = trafficLights,
        )
    }

    /**
     * 步行 linkType：0 普通道路，1 人行横道，2 地下通道，3 过街天桥，
     * 4 广场/公园内部道路，5 扶梯，6 直梯。
     */
    private fun inferFacility(linkType: Int, roadName: String): FacilityType {
        val name = roadName
        return when {
            name.contains("人行横道") || name.contains("过街") || name.contains("斑马线") ->
                FacilityType.Crosswalk
            name.contains("天桥") || linkType == 3 -> FacilityType.Bridge
            name.contains("地下通道") || name.contains("隧道") || linkType == 2 -> FacilityType.Tunnel
            name.contains("电梯") || linkType == 6 -> FacilityType.Lift
            name.contains("扶梯") || linkType == 5 -> FacilityType.Escalator
            else -> FacilityType.None
        }
    }

    private fun polylineOrientation(points: List<GeoPoint>): String {
        if (points.size < 2) return ""
        return GeoMath.compassDirection(GeoMath.bearingDegrees(points.first(), points[1]))
    }

    private fun NaviLatLng.toGeo(): GeoPoint = GeoPoint(latitude, longitude)

    private fun markTrafficLights(steps: List<WalkStep>, lights: List<GeoPoint>, sdkCount: Int): List<WalkStep> {
        if (lights.isNotEmpty()) {
            val owners = steps.map { BooleanArray(it.links.size) }
            lights.forEach { light ->
                var bestStep = -1
                var bestLink = -1
                var bestDist = 45.0
                steps.forEachIndexed { si, step ->
                    step.links.forEachIndexed { li, link ->
                        if (link.polyline.size < 2) return@forEachIndexed
                        val dist = GeoMath.distanceToPolyline(light, link.polyline)
                        if (dist < bestDist) {
                            bestDist = dist
                            bestStep = si
                            bestLink = li
                        }
                    }
                }
                if (bestStep >= 0) owners[bestStep][bestLink] = true
            }
            return steps.mapIndexed { si, step ->
                val newLinks = step.links.mapIndexed { li, link ->
                    link.copy(hasTrafficLight = owners[si].getOrElse(li) { false })
                }
                step.copy(links = newLinks, trafficLightCount = newLinks.count { it.hasTrafficLight })
            }
        }
        var remaining = sdkCount
        return steps.map { step ->
            if (remaining <= 0 || !step.isTurn) {
                step.copy(
                    links = step.links.map { it.copy(hasTrafficLight = false) },
                    trafficLightCount = 0,
                )
            } else {
                val litIndex = step.links.indexOfLast { it.hasTrafficLight }.takeIf { it >= 0 }
                    ?: step.links.lastIndex.takeIf { step.links.isNotEmpty() }
                if (litIndex == null || litIndex < 0) {
                    step.copy(
                        links = step.links.map { it.copy(hasTrafficLight = false) },
                        trafficLightCount = 0,
                    )
                } else {
                    remaining--
                    val newLinks = step.links.mapIndexed { index, link ->
                        link.copy(hasTrafficLight = index == litIndex)
                    }
                    step.copy(links = newLinks, trafficLightCount = 1)
                }
            }
        }
    }

    private const val TAG = "PathMapper"
    private val LIGHTS_REGEX = Regex("过(\\d+)个红绿灯")
}
