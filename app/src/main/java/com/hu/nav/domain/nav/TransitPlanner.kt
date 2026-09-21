package com.hu.nav.domain.nav

import com.hu.nav.domain.model.GeoMath
import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.TransitCopy
import com.hu.nav.domain.model.TransitKind
import com.hu.nav.domain.model.TransitSegment
import com.hu.nav.domain.model.TransitStop
import com.hu.nav.domain.model.WalkPath

class TransitPlanner {
    var path: WalkPath? = null
        private set
    var segmentIndex: Int = 0
        private set
    var destinationName: String = ""
        private set
    private var lastNextStop: String = ""
    private var approachingAlight: Boolean = false
    private var lastRidePoint: GeoPoint? = null

    val currentSegment: TransitSegment?
        get() = path?.transitSegments?.getOrNull(segmentIndex)

    val riding: Boolean
        get() = currentSegment?.isRide == true

    fun start(path: WalkPath, destinationName: String): List<GuideEvent> {
        this.path = path
        this.destinationName = destinationName
        segmentIndex = 0
        lastNextStop = ""
        approachingAlight = false
        lastRidePoint = null
        val events = mutableListOf<GuideEvent>(GuideEvent.TransitPrompt(path.overviewText()))
        events += promptForCurrent()
        return events
    }

    fun stop() {
        path = null
        segmentIndex = 0
        lastNextStop = ""
        approachingAlight = false
        lastRidePoint = null
        destinationName = ""
    }

    fun currentWalkPath(): WalkPath? {
        val segment = currentSegment?.takeIf { it.kind == TransitKind.Walk } ?: return null
        val guides = segment.walkGuides.ifEmpty {
            listOf(
                com.hu.nav.domain.model.RouteGuide(
                    instruction = segment.instruction,
                    action = "步行",
                    lengthMeters = segment.distanceMeters,
                    polyline = segment.polyline,
                ),
            )
        }
        return WalkPath(
            id = segmentIndex,
            distanceMeters = segment.distanceMeters.coerceAtLeast(1),
            durationSeconds = segment.durationSeconds.coerceAtLeast(1),
            steps = emptyList(),
            polyline = segment.polyline,
            guides = guides,
            originAddress = "",
        )
    }

    fun currentWalkTargetName(): String {
        val segment = currentSegment ?: return destinationName
        return segment.arrival?.name?.ifBlank { destinationName } ?: destinationName
    }

    fun currentWalkTargetPoint(): GeoPoint? = currentSegment?.targetPoint()

    fun remainMeters(location: GeoPoint?): Int {
        val path = path ?: return 0
        val segs = path.transitSegments
        if (segs.isEmpty()) return path.distanceMeters
        var sum = 0
        segs.forEachIndexed { index, segment ->
            if (index < segmentIndex) return@forEachIndexed
            val line = segment.polyline
            sum += if (index == segmentIndex && location != null && line.size >= 2) {
                GeoMath.remainAlongPolyline(location, line).toInt()
            } else {
                segment.distanceMeters
            }
        }
        return sum.coerceAtLeast(0)
    }

    fun remainSeconds(location: GeoPoint?): Int {
        val path = path ?: return 0
        val totalDist = path.distanceMeters.coerceAtLeast(1)
        val remain = remainMeters(location).coerceAtLeast(0)
        return ((path.durationSeconds.toLong() * remain) / totalDist).toInt().coerceAtLeast(if (remain > 0) 1 else 0)
    }

    fun currentHint(): String = currentSegment?.instruction.orEmpty()

    fun onWalkArrived(): List<GuideEvent> {
        val events = mutableListOf<GuideEvent>()
        val arrivedName = currentWalkTargetName()
        if (arrivedName.isNotBlank() && arrivedName != destinationName) {
            events += GuideEvent.TransitPrompt("已到达$arrivedName")
        }
        return events + advance()
    }

    fun onRideLocation(point: GeoPoint): List<GuideEvent> {
        val segment = currentSegment?.takeIf { it.isRide } ?: return emptyList()
        lastRidePoint = point
        val line = segment.polyline
        val arrival = segment.arrival
        val remain = if (line.size >= 2) GeoMath.remainAlongPolyline(point, line) else {
            arrival?.location?.let { GeoMath.distanceMeters(point, it) } ?: Double.POSITIVE_INFINITY
        }
        val toStop = arrival?.location?.let { GeoMath.distanceMeters(point, it) } ?: remain
        val events = mutableListOf<GuideEvent>()
        if (!approachingAlight && (remain <= 120 || toStop <= 80)) {
            approachingAlight = true
            events += GuideEvent.TransitPrompt(TransitCopy.alight(arrival?.name.orEmpty()))
        }
        val next = nextStop(point, segment)
        if (next != null && next.name.isNotBlank() && next.name != lastNextStop && next.name != arrival?.name) {
            lastNextStop = next.name
            events += GuideEvent.TransitPrompt(TransitCopy.nextStop(next.name))
        }
        if (remain <= 40 || toStop <= 45) {
            return events + advance()
        }
        return events
    }

    fun advance(): List<GuideEvent> {
        val path = path ?: return listOf(GuideEvent.Arrived(destinationName))
        val segs = path.transitSegments
        if (segmentIndex >= segs.lastIndex) {
            return listOf(GuideEvent.Arrived(destinationName))
        }
        segmentIndex += 1
        lastNextStop = ""
        approachingAlight = false
        return promptForCurrent()
    }

    fun finished(): Boolean {
        val segs = path?.transitSegments.orEmpty()
        return segs.isEmpty() || segmentIndex >= segs.size
    }

    private fun promptForCurrent(): List<GuideEvent> {
        val segment = currentSegment ?: return listOf(GuideEvent.Arrived(destinationName))
        val text = if (segment.isRide) {
            TransitCopy.boarding(segment.lineName, segment.departure?.name.orEmpty()) +
                "。${segment.instruction}"
        } else {
            segment.instruction
        }
        return listOf(GuideEvent.TransitPrompt(text))
    }

    private fun nextStop(point: GeoPoint, segment: TransitSegment): TransitStop? {
        val stops = segment.allStops().filter { it.location != null && it.name.isNotBlank() }
        if (stops.isEmpty()) return segment.arrival
        val line = segment.polyline
        if (line.size < 2) {
            return stops.minByOrNull { GeoMath.distanceMeters(point, it.location!!) }
        }
        val remain = GeoMath.remainAlongPolyline(point, line)
        val ahead = stops.mapNotNull { stop ->
            val loc = stop.location ?: return@mapNotNull null
            val stopRemain = GeoMath.remainAlongPolyline(loc, line)
            if (stopRemain < remain - 8) stop to stopRemain else null
        }
        return ahead.maxByOrNull { it.second }?.first
    }
}
