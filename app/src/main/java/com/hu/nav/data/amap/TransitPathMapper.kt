package com.hu.nav.data.amap

import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.BusPath
import com.amap.api.services.route.BusStep
import com.amap.api.services.route.RouteBusLineItem
import com.amap.api.services.route.RouteBusWalkItem
import com.amap.api.services.route.WalkStep as AmapWalkStep
import com.hu.nav.domain.model.GeoMath
import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.RouteDetailFormatter
import com.hu.nav.domain.model.RouteGuide
import com.hu.nav.domain.model.SearchWalkStep
import com.hu.nav.domain.model.TransitCopy
import com.hu.nav.domain.model.TransitKind
import com.hu.nav.domain.model.TransitSegment
import com.hu.nav.domain.model.TransitStop
import com.hu.nav.domain.model.TravelMode
import com.hu.nav.domain.model.WalkPath

object TransitPathMapper {
    fun toWalkPath(id: Int, path: BusPath, originAddress: String): WalkPath {
        val segments = mutableListOf<TransitSegment>()
        path.steps.orEmpty().forEach { step ->
            walkSegment(step)?.let { segments += it }
            rideSegment(step)?.let { segments += it }
            railwaySegment(step)?.let { segments += it }
            taxiSegment(step)?.let { segments += it }
        }
        val polyline = mutableListOf<GeoPoint>()
        segments.forEach { GeoMath.appendPolyline(polyline, it.polyline) }
        if (polyline.isEmpty()) {
            GeoMath.appendPolyline(polyline, path.polyline.orEmpty().map { it.toGeo() })
        }
        val rides = segments.filter { it.isRide }
        val lineNames = rides.map { it.lineName }.filter { it.isNotBlank() }.distinct()
        val guides = guidesOf(segments, originAddress)
        val walkMeters = path.walkDistance.toInt().takeIf { it > 0 }
            ?: segments.filter { it.kind == TransitKind.Walk }.sumOf { it.distanceMeters }
        return WalkPath(
            id = id,
            distanceMeters = path.distance.toInt().coerceAtLeast(polylineLength(polyline)),
            durationSeconds = path.duration.toInt().coerceAtLeast(1),
            steps = emptyList(),
            polyline = polyline,
            guides = guides,
            originAddress = originAddress,
            mode = TravelMode.Transit,
            costYuan = path.cost,
            walkDistanceMeters = walkMeters,
            transferCount = (rides.size - 1).coerceAtLeast(0),
            lineSummary = lineNames.joinToString(" → "),
            transitSegments = segments,
        )
    }

    private fun walkSegment(step: BusStep): TransitSegment? {
        val walk = step.walk ?: return null
        val meters = walk.distance.toInt()
        val seconds = walk.duration.toInt()
        val line = walkPolyline(walk)
        if (meters <= 0 && seconds <= 0 && line.size < 2 && walk.steps.isNullOrEmpty()) return null
        val destName = step.entrance?.name.orEmpty()
            .ifBlank { step.busLine?.departureBusStation?.busStationName.orEmpty() }
            .ifBlank { step.busLines.orEmpty().firstOrNull()?.departureBusStation?.busStationName.orEmpty() }
        val walkGuides = walk.steps.orEmpty().map { it.toSearchStep() }
            .let { RouteDetailFormatter.fromSearchSteps(it, "", includeStart = false) }
            .ifEmpty {
                listOf(
                    RouteGuide(
                        instruction = TransitCopy.walkTo(meters.coerceAtLeast(1), destName),
                        action = "步行",
                        lengthMeters = meters,
                        polyline = line,
                    ),
                )
            }
        val instruction = walkGuides.joinToString("，") { it.instruction }.ifBlank {
            TransitCopy.walkTo(meters.coerceAtLeast(1), destName)
        }
        return TransitSegment(
            kind = TransitKind.Walk,
            instruction = instruction,
            arrival = destName.takeIf { it.isNotBlank() }?.let { TransitStop(it, line.lastOrNull()) },
            distanceMeters = meters.coerceAtLeast(walkGuides.sumOf { it.lengthMeters }),
            durationSeconds = seconds,
            polyline = line.ifEmpty { walkGuides.flatMap { it.polyline } },
            walkGuides = walkGuides,
        )
    }

    private fun rideSegment(step: BusStep): TransitSegment? {
        val lines = step.busLines.orEmpty().ifEmpty { listOfNotNull(step.busLine) }
        val line = lines.firstOrNull() ?: return null
        val names = lines.map { TransitCopy.shortLineName(it.busLineName.orEmpty()) }
            .filter { it.isNotBlank() }
            .distinct()
        val lineName = names.joinToString("或")
        val kind = TransitCopy.kindOf(lineName, line.busLineType.orEmpty())
        val departure = line.departureBusStation.toStop()
        val arrival = line.arrivalBusStation.toStop()
        val via = line.passStations.orEmpty().map { it.toStop() }
        val polyline = line.polyline.orEmpty().map { it.toGeo() }.ifEmpty {
            listOfNotNull(departure.location, arrival.location)
        }
        val entrance = step.entrance?.name.orEmpty()
        val exit = step.exit?.name.orEmpty()
        val instruction = TransitCopy.rideInstruction(
            kind = kind,
            lineName = lineName,
            departure = departure.name,
            arrival = arrival.name,
            passStationCount = line.passStationNum.coerceAtLeast(via.size),
            entrance = entrance,
            exit = exit,
        )
        return TransitSegment(
            kind = kind,
            instruction = instruction,
            lineName = lineName,
            departure = departure,
            arrival = arrival,
            viaStops = via,
            distanceMeters = line.distance.toInt(),
            durationSeconds = line.duration.toInt(),
            polyline = polyline,
        )
    }

    private fun railwaySegment(step: BusStep): TransitSegment? {
        val rail = step.railway ?: return null
        val name = TransitCopy.shortLineName(rail.name.orEmpty().ifBlank { rail.trip.orEmpty() })
        val dep = rail.departurestop?.let { TransitStop(it.name.orEmpty(), it.location?.toGeo()) }
        val arr = rail.arrivalstop?.let { TransitStop(it.name.orEmpty(), it.location?.toGeo()) }
        val via = rail.viastops.orEmpty().map { TransitStop(it.name.orEmpty(), it.location?.toGeo()) }
        val polyline = listOfNotNull(dep?.location) +
            via.mapNotNull { it.location } +
            listOfNotNull(arr?.location)
        return TransitSegment(
            kind = TransitKind.Railway,
            instruction = TransitCopy.rideInstruction(
                kind = TransitKind.Railway,
                lineName = name.ifBlank { "火车" },
                departure = dep?.name.orEmpty(),
                arrival = arr?.name.orEmpty(),
                passStationCount = via.size,
            ),
            lineName = name.ifBlank { "火车" },
            departure = dep,
            arrival = arr,
            viaStops = via,
            distanceMeters = rail.distance.toInt(),
            polyline = polyline,
        )
    }

    private fun taxiSegment(step: BusStep): TransitSegment? {
        val taxi = step.taxi ?: return null
        val from = taxi.origin?.toGeo()
        val to = taxi.destination?.toGeo()
        val depName = taxi.getmSname().orEmpty()
        val arrName = taxi.getmTname().orEmpty()
        return TransitSegment(
            kind = TransitKind.Taxi,
            instruction = TransitCopy.rideInstruction(
                kind = TransitKind.Taxi,
                lineName = "出租车",
                departure = depName,
                arrival = arrName,
                passStationCount = 0,
            ),
            lineName = "出租车",
            departure = TransitStop(depName, from),
            arrival = TransitStop(arrName, to),
            distanceMeters = taxi.distance.toInt(),
            durationSeconds = taxi.duration.toInt(),
            polyline = listOfNotNull(from, to),
        )
    }

    private fun guidesOf(segments: List<TransitSegment>, originAddress: String): List<RouteGuide> {
        val guides = mutableListOf(RouteDetailFormatter.startGuide(originAddress))
        segments.forEach { segment ->
            if (segment.kind == TransitKind.Walk && segment.walkGuides.isNotEmpty()) {
                guides += segment.walkGuides
            } else {
                guides += RouteGuide(
                    instruction = segment.instruction,
                    action = if (segment.isRide) "乘车" else "步行",
                    roadName = segment.lineName,
                    lengthMeters = segment.distanceMeters,
                    polyline = segment.polyline,
                )
            }
        }
        if (guides.none { it.isEnd || it.instruction.contains("到达") }) {
            guides += RouteGuide(instruction = "到达目的地", action = "到达", isEnd = true)
        }
        return guides
    }

    private fun walkPolyline(walk: RouteBusWalkItem): List<GeoPoint> {
        val fromPath = walk.polyline.orEmpty().map { it.toGeo() }
        val fromSteps = walk.steps.orEmpty().flatMap { step -> step.polyline.orEmpty().map { it.toGeo() } }
        return listOf(fromPath, fromSteps).maxBy { it.size }
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
            polyline = polyline.orEmpty().map { it.toGeo() },
        )
    }

    private fun com.amap.api.services.busline.BusStationItem?.toStop(): TransitStop {
        val item = this
        return TransitStop(
            name = item?.busStationName.orEmpty(),
            location = item?.latLonPoint?.toGeo(),
        )
    }

    private fun LatLonPoint.toGeo(): GeoPoint = GeoPoint(latitude, longitude)

    private fun polylineLength(line: List<GeoPoint>): Int = GeoMath.polylineLength(line).toInt()
}
