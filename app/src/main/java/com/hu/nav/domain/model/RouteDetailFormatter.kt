package com.hu.nav.domain.model

object RouteDetailFormatter {
    private val TURN_WORDS = listOf(
        "左转", "右转", "向左前方", "向右前方", "向左后方", "向右后方",
        "左前方", "右前方", "左后方", "右后方", "掉头", "进入环岛", "驶出环岛",
        "后左", "后右",
    )
    private val LIGHTS_REGEX = Regex("过(\\d+)个红绿灯")

    fun startGuide(originAddress: String): RouteGuide {
        val place = originAddress.trim()
        val text = if (place.isBlank()) {
            "从「我的位置」出发"
        } else {
            "从「我的位置（$place）」出发"
        }
        return RouteGuide(instruction = text, action = "出发", isStart = true)
    }

    fun fromSearchSteps(
        steps: List<SearchWalkStep>,
        originAddress: String,
        includeStart: Boolean = true,
    ): List<RouteGuide> {
        val guides = mutableListOf<RouteGuide>()
        if (includeStart) guides += startGuide(originAddress)
        steps.forEachIndexed { index, step ->
            val isLast = index == steps.lastIndex
            val facility = facilityOf(step.roadType, step.assistantAction, step.instruction)
            val lights = parseLights(step.instruction).coerceAtLeast(step.trafficLights)
            val instruction = decorateInstruction(
                raw = step.instruction,
                orientation = step.orientation,
                roadName = step.road,
                meters = step.distanceMeters,
                action = step.action,
                assistantAction = step.assistantAction,
                trafficLights = lights,
                facility = facility,
                isLast = isLast,
            )
            guides += RouteGuide(
                instruction = instruction,
                action = step.action,
                roadName = step.road,
                lengthMeters = step.distanceMeters,
                trafficLights = parseLights(instruction).coerceAtLeast(lights),
                facility = facility,
                isEnd = isLast || instruction.contains("到达"),
                polyline = step.polyline,
            )
        }
        return guides
    }

    fun detectCrosswalks(steps: List<WalkStep>): List<WalkStep> {
        val flat = steps.flatMapIndexed { si, step ->
            step.links.mapIndexed { li, link -> Triple(si, li, link) }
        }
        if (flat.isEmpty()) return steps
        val marked = Array(steps.size) { si -> BooleanArray(steps[si].links.size) }
        fun named(link: WalkLink?): String {
            val name = link?.roadName.orEmpty()
            return if (name.isBlank() || name == "无名道路") "" else name
        }
        flat.forEachIndexed { index, (si, li, link) ->
            val already = link.facility == FacilityType.Crosswalk ||
                link.roadName.contains("人行横道") ||
                link.roadName.contains("斑马线") ||
                link.roadName.contains("过街")
            if (already) {
                marked[si][li] = true
                return@forEachIndexed
            }
            val prev = flat.getOrNull(index - 1)?.third
            val next = flat.getOrNull(index + 1)?.third
            val unnamed = named(link).isEmpty()
            val short = link.lengthMeters in 4..32
            val prevName = named(prev)
            val nextName = named(next)
            val streetChange = prevName.isNotEmpty() && nextName.isNotEmpty() && prevName != nextName
            val sameStreetGap = prevName.isNotEmpty() && prevName == nextName && unnamed
            if (short && unnamed && (streetChange || sameStreetGap)) {
                marked[si][li] = true
            }
        }
        return steps.mapIndexed { si, step ->
            step.copy(
                links = step.links.mapIndexed { li, link ->
                    if (marked[si][li] && link.facility == FacilityType.None) {
                        link.copy(facility = FacilityType.Crosswalk)
                    } else {
                        link
                    }
                },
            )
        }
    }

    fun enrichSearchSteps(
        search: List<SearchWalkStep>,
        crossingPoints: List<GeoPoint>,
        lightPoints: List<GeoPoint>,
        sdkLightCount: Int = 0,
    ): List<SearchWalkStep> {
        if (search.isEmpty()) return search
        val lightsPerStep = Array(search.size) { mutableListOf<GeoPoint>() }
        lightPoints.forEach { point ->
            val idx = nearestSearchStep(search, point, 50.0)
            if (idx >= 0) lightsPerStep[idx] += point
        }
        if (lightsPerStep.all { it.isEmpty() } && sdkLightCount > 0) {
            val turns = search.mapIndexedNotNull { index, step ->
                index.takeIf { isTurnText(step.action + step.instruction) && !step.instruction.contains("到达") }
            }
            val targets = if (turns.isNotEmpty()) turns.takeLast(sdkLightCount.coerceAtMost(turns.size))
            else search.indices.toList().drop(1).takeLast(sdkLightCount)
            targets.forEach { lightsPerStep[it] += GeoPoint(0.0, 0.0) }
        }
        val crossesPerStep = Array(search.size) { mutableListOf<GeoPoint>() }
        crossingPoints.forEach { point ->
            val idx = nearestSearchStep(search, point, 50.0)
            if (idx >= 0) crossesPerStep[idx] += point
        }
        if (crossingPoints.isNotEmpty() && crossesPerStep.all { it.isEmpty() }) {
            val candidates = search.indices.filter { index ->
                val step = search[index]
                !step.instruction.contains("到达") && step.distanceMeters >= 30
            }
            candidates.takeLast(crossingPoints.size.coerceAtMost(candidates.size)).forEach { index ->
                crossesPerStep[index] += crossingPoints.first()
            }
        }
        return search.flatMapIndexed { index, step ->
            splitSearchStep(
                step = step,
                lights = lightsPerStep[index],
                crossings = crossesPerStep[index],
                isLast = index == search.lastIndex,
            )
        }
    }

    fun splitSearchStep(
        step: SearchWalkStep,
        lights: List<GeoPoint>,
        crossings: List<GeoPoint>,
        isLast: Boolean,
    ): List<SearchWalkStep> {
        val hasDummyLight = lights.any { !it.isValid() }
        val realLights = lights.filter { it.isValid() }
        val events = mutableListOf<Pair<Double, String>>()
        realLights.forEach { events += alongMeters(it, step.polyline) to "light" }
        crossings.filter { it.isValid() }.forEach { events += alongMeters(it, step.polyline) to "cross" }
        val clustered = clusterEvents(events, mergeWithinMeters = 30.0)
        if (clustered.isEmpty()) {
            val inferCross = shouldInferCrosswalk(step) && !hasDummyLight
            return listOf(
                step.copy(
                    trafficLights = if (hasDummyLight) 1 else 0,
                    assistantAction = when {
                        step.assistantAction.isNotBlank() -> step.assistantAction
                        inferCross -> "过马路"
                        else -> ""
                    },
                    roadType = if (inferCross && step.roadType == 0) 1 else step.roadType,
                ),
            )
        }
        val pieces = mutableListOf<SearchWalkStep>()
        var cursor = 0.0
        clustered.forEachIndexed { index, (along, kinds) ->
            val lastEvent = index == clustered.lastIndex
            val end = along.coerceAtLeast(cursor + 1.0)
            val meters = (end - cursor).toInt().coerceAtLeast(1)
            val lightCount = kinds.count { it == "light" }.coerceAtMost(1)
            val assistant = when {
                lightCount > 0 -> ""
                kinds.contains("cross") -> "过马路"
                else -> ""
            }
            pieces += step.copy(
                instruction = "",
                distanceMeters = meters,
                action = if (lastEvent) step.action else "",
                assistantAction = assistant,
                roadType = if (assistant == "过马路") 1 else step.roadType,
                trafficLights = lightCount,
                polyline = GeoMath.slicePolyline(step.polyline, cursor, end),
            )
            cursor = end
        }
        val remain = step.distanceMeters - pieces.sumOf { it.distanceMeters }
        if (remain > 0 && pieces.isNotEmpty()) {
            val last = pieces.last()
            val lastStart = cursor - last.distanceMeters
            pieces[pieces.lastIndex] = last.copy(
                distanceMeters = last.distanceMeters + remain,
                action = step.action,
                polyline = GeoMath.slicePolyline(step.polyline, lastStart, lastStart + last.distanceMeters + remain),
            )
        }
        return pieces.ifEmpty { listOf(step) }
    }

    private fun shouldInferCrosswalk(step: SearchWalkStep): Boolean {
        if (step.instruction.contains("到达") || step.assistantAction.contains("到达")) return false
        if (step.trafficLights > 0) return false
        val named = step.road.isNotBlank() && step.road != "无名道路"
        return named && step.distanceMeters >= 80 && isTurnText(step.action + step.instruction)
    }

    private fun alongMeters(point: GeoPoint, line: List<GeoPoint>): Double {
        if (line.size < 2) return 0.0
        var bestIndex = 0
        var bestDist = Double.POSITIVE_INFINITY
        line.forEachIndexed { index, vertex ->
            val dist = GeoMath.distanceMeters(point, vertex)
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = index
            }
        }
        var along = 0.0
        for (i in 0 until bestIndex) {
            along += GeoMath.distanceMeters(line[i], line[i + 1])
        }
        return along
    }

    private fun clusterEvents(
        events: List<Pair<Double, String>>,
        mergeWithinMeters: Double,
    ): List<Pair<Double, List<String>>> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.sortedBy { it.first }
        val clusters = mutableListOf<Pair<Double, MutableList<String>>>()
        sorted.forEach { (along, kind) ->
            val last = clusters.lastOrNull()
            if (last != null && along - last.first < mergeWithinMeters) {
                last.second += kind
            } else {
                clusters += along to mutableListOf(kind)
            }
        }
        return clusters.map { it.first to it.second.toList() }
    }

    fun linkMidpoint(link: WalkLink): GeoPoint? {
        val line = link.polyline
        if (line.isEmpty()) return null
        return line[line.size / 2]
    }

    private fun nearestSearchStep(steps: List<SearchWalkStep>, point: GeoPoint, maxMeters: Double): Int {
        var best = -1
        var bestDist = maxMeters
        steps.forEachIndexed { index, step ->
            val line = step.polyline
            val dist = when {
                line.size >= 2 -> GeoMath.distanceToPolyline(point, line)
                line.size == 1 -> GeoMath.distanceMeters(point, line.first())
                else -> Double.POSITIVE_INFINITY
            }
            if (dist < bestDist) {
                bestDist = dist
                best = index
            }
        }
        return best
    }

    /**
     * 用导航 link 还原过马路 / 红绿灯。导航 step 文案通常不含这些，信息在 linkType 和红绿灯点上。
     */
    fun fromNaviLinks(steps: List<WalkStep>): List<RouteGuide> {
        if (steps.isEmpty()) return emptyList()
        val guides = mutableListOf<RouteGuide>()
        var accMeters = 0
        var accRoad = ""
        var accOrient = ""
        var accLights = 0
        val accLine = mutableListOf<GeoPoint>()

        fun named(name: String): String =
            if (name.isBlank() || name == "无名道路") "" else name

        fun emit(action: String, facility: FacilityType, isEnd: Boolean) {
            if (accMeters <= 0 && !isEnd && facility == FacilityType.None && accLights == 0 && action.isBlank()) {
                return
            }
            val lights = accLights
            guides += RouteGuide(
                instruction = buildInstruction(
                    orientation = accOrient,
                    roadName = accRoad,
                    meters = accMeters,
                    action = action,
                    assistantAction = if (facility == FacilityType.Crosswalk) "过马路" else "",
                    trafficLights = lights,
                    facility = facility,
                    isLast = isEnd,
                ),
                action = action,
                roadName = accRoad,
                lengthMeters = accMeters,
                trafficLights = lights,
                facility = facility,
                isEnd = isEnd,
                polyline = accLine.toList(),
            )
            accMeters = 0
            accLights = 0
            accLine.clear()
        }

        steps.forEachIndexed { stepIndex, step ->
            val lastStep = stepIndex == steps.lastIndex
            val links = step.links.ifEmpty {
                listOf(
                    WalkLink(
                        polyline = emptyList(),
                        lengthMeters = step.lengthMeters,
                        roadName = step.roadName,
                        facility = step.facility,
                        hasTrafficLight = step.trafficLightCount > 0,
                    ),
                )
            }
            if (accOrient.isBlank()) {
                accOrient = step.orientation.ifBlank {
                    links.firstOrNull()?.expectedBearing?.let { GeoMath.compassDirection(it) }.orEmpty()
                }
            }
            links.forEach { link ->
                val linkRoad = named(link.roadName)
                if (accRoad.isBlank()) accRoad = linkRoad.ifBlank { named(step.roadName) }
                val crossing = when (link.facility) {
                    FacilityType.Crosswalk, FacilityType.Bridge, FacilityType.Tunnel -> link.facility
                    else -> when {
                        link.roadName.contains("人行横道") ||
                            link.roadName.contains("过街") ||
                            link.roadName.contains("斑马线") -> FacilityType.Crosswalk
                        else -> FacilityType.None
                    }
                }
                if (crossing != FacilityType.None) {
                    accMeters += link.lengthMeters
                    GeoMath.appendPolyline(accLine, link.polyline)
                    val action = when (crossing) {
                        FacilityType.Bridge -> "过天桥"
                        FacilityType.Tunnel -> "过地下通道"
                        else -> "过马路"
                    }
                    emit(action, crossing, isEnd = false)
                    accRoad = ""
                    accOrient = ""
                    return@forEach
                }
                if (linkRoad.isNotBlank() && accRoad.isNotBlank() && linkRoad != accRoad && accMeters > 0) {
                    emit("进入$linkRoad", FacilityType.None, isEnd = false)
                    accRoad = linkRoad
                }
                accMeters += link.lengthMeters
                GeoMath.appendPolyline(accLine, link.polyline)
                if (link.hasTrafficLight) accLights += 1
            }
            val stepIsCrossing = step.facility == FacilityType.Crosswalk ||
                step.instruction.contains("过马路") ||
                step.instruction.contains("过街") ||
                step.instruction.contains("人行横道")
            if (stepIsCrossing && guides.lastOrNull()?.facility != FacilityType.Crosswalk) {
                emit("过马路", FacilityType.Crosswalk, isEnd = false)
                accRoad = ""
                accOrient = ""
            }
            val tinyTurn = step.isTurn &&
                !lastStep &&
                step.lengthMeters < 30 &&
                named(step.roadName).isEmpty() &&
                step.links.none { it.facility == FacilityType.Crosswalk }
            if ((step.isTurn || lastStep) && !tinyTurn) {
                val action = when {
                    lastStep && (step.iconType == 11 || step.action.contains("到达")) -> "到达目的地"
                    step.isTurn -> step.action.ifBlank { step.orientation }
                    else -> ""
                }
                emit(action, FacilityType.None, isEnd = lastStep)
                accRoad = ""
                accOrient = ""
            }
        }
        if (accMeters > 0 || accLights > 0) {
            emit("", FacilityType.None, isEnd = false)
        }
        return guides
    }

    fun fromNaviSteps(steps: List<WalkStep>): List<RouteGuide> {
        if (steps.isEmpty()) return emptyList()
        val guides = mutableListOf<RouteGuide>()
        val bucket = mutableListOf<WalkStep>()

        fun flush(closing: WalkStep?) {
            val all = if (closing != null) bucket + closing else bucket.toList()
            bucket.clear()
            if (all.isEmpty()) return
            val facility = all.map { it.facility }.firstOrNull { it != FacilityType.None } ?: FacilityType.None
            val lights = all.sumOf { it.trafficLightCount + it.links.count { link -> link.hasTrafficLight } }
            val action = closing?.action?.ifBlank { closing.orientation }.orEmpty()
            val first = all.first()
            val orientation = first.orientation.ifBlank {
                first.links.firstOrNull()?.expectedBearing?.let { GeoMath.compassDirection(it) }.orEmpty()
            }
            val road = namedRoad(all)
            val isLast = closing != null && (closing.iconType == 11 || closing.action.contains("到达"))
            val line = mutableListOf<GeoPoint>()
            all.forEach { step ->
                step.links.forEach { GeoMath.appendPolyline(line, it.polyline) }
            }
            guides += RouteGuide(
                instruction = buildInstruction(
                    orientation = orientation,
                    roadName = road,
                    meters = all.sumOf { it.lengthMeters },
                    action = action,
                    assistantAction = if (facility == FacilityType.Crosswalk) "过马路" else "",
                    trafficLights = lights,
                    facility = facility,
                    isLast = isLast,
                ),
                action = action,
                roadName = road,
                lengthMeters = all.sumOf { it.lengthMeters },
                trafficLights = lights,
                facility = facility,
                isEnd = isLast,
                polyline = line,
            )
        }

        steps.forEachIndexed { index, step ->
            val last = index == steps.lastIndex
            val shouldClose = last ||
                step.isTurn ||
                step.facility != FacilityType.None ||
                step.trafficLightCount > 0 ||
                step.links.any { it.hasTrafficLight }
            if (shouldClose) {
                flush(step)
            } else {
                bucket += step
            }
        }
        if (bucket.isNotEmpty()) flush(null)
        return guides
    }

    fun turnCount(guides: List<RouteGuide>): Int =
        guides.count { !it.isStart && isTurnText(it.action + it.instruction) }

    fun trafficLightCount(guides: List<RouteGuide>): Int {
        val fromField = guides.sumOf { it.trafficLights }
        if (fromField > 0) return fromField
        return guides.sumOf { parseLights(it.instruction) }
    }

    fun crossStreetCount(guides: List<RouteGuide>): Int =
        guides.count { !it.isStart && isCrossStreet(it) }

    fun decorateInstruction(
        raw: String,
        orientation: String,
        roadName: String,
        meters: Int,
        action: String,
        assistantAction: String,
        trafficLights: Int,
        facility: FacilityType,
        isLast: Boolean,
    ): String {
        val built = buildInstruction(
            orientation = orientation,
            roadName = roadName,
            meters = meters,
            action = action,
            assistantAction = assistantAction,
            trafficLights = trafficLights,
            facility = facility,
            isLast = isLast,
        )
        if (raw.isBlank()) return built
        var text = raw.trim().trimEnd('。', '，', ',', ' ')
        if ((facility == FacilityType.Crosswalk || assistantAction.contains("过马路")) &&
            !text.contains("过马路") && !text.contains("人行横道")
        ) {
            text += "，过马路"
        }
        if (facility == FacilityType.Bridge && !text.contains("天桥")) text += "，过天桥"
        if (facility == FacilityType.Tunnel && !text.contains("地下") && !text.contains("地道")) {
            text += "，过地下通道"
        }
        if (trafficLights > 0 && !text.contains("红绿灯")) {
            val turn = shortTurn(action)
            text += if (turn.isBlank()) "，过${trafficLights}个红绿灯" else "，过${trafficLights}个红绿灯后$turn"
        }
        return text
    }

    fun crossingSignalCount(guides: List<RouteGuide>): Int =
        crossStreetCount(guides) + trafficLightCount(guides)

    fun buildInstruction(
        orientation: String,
        roadName: String,
        meters: Int,
        action: String,
        assistantAction: String,
        trafficLights: Int,
        facility: FacilityType,
        isLast: Boolean,
    ): String {
        if (isLast && meters <= 0) return "到达目的地"
        val heading = orientation.removePrefix("向").trim()
        val road = when {
            roadName.isBlank() || roadName == "无名道路" -> "道路"
            else -> roadName
        }
        val walk = buildString {
            if (heading.isNotBlank()) append("向").append(heading)
            append("沿").append(road)
            if (meters > 0) append("行走").append(meters).append("米")
        }
        val suffix = when {
            facility == FacilityType.Crosswalk || assistantAction.contains("过马路") -> "，过马路"
            facility == FacilityType.Bridge || assistantAction.contains("天桥") -> "，过天桥"
            facility == FacilityType.Tunnel || assistantAction.contains("地道") || assistantAction.contains("地下通道") -> "，过地下通道"
            trafficLights > 0 -> {
                val turn = shortTurn(action)
                if (turn.isBlank()) "，过${trafficLights}个红绿灯" else "，过${trafficLights}个红绿灯后$turn"
            }
            shortTurn(action).isNotBlank() -> "，${shortTurn(action)}"
            action.contains("进入") -> "，$action"
            isLast -> "，到达目的地"
            else -> ""
        }
        return walk + suffix
    }

    private fun namedRoad(steps: List<WalkStep>): String {
        return steps.map { it.roadName }.firstOrNull { it.isNotBlank() && it != "无名道路" }
            ?: steps.firstOrNull { it.roadName.isNotBlank() }?.roadName.orEmpty()
    }

    private fun isTurnText(text: String): Boolean = TURN_WORDS.any { text.contains(it) }

    private fun isCrossStreet(guide: RouteGuide): Boolean {
        if (guide.facility == FacilityType.Crosswalk) return true
        val text = guide.instruction + guide.action
        return text.contains("过马路") || text.contains("人行横道") || text.contains("过街")
    }

    private fun parseLights(text: String): Int {
        val match = LIGHTS_REGEX.find(text)
        if (match != null) return match.groupValues[1].toIntOrNull() ?: 0
        if (text.contains("红绿灯")) return 1
        return 0
    }

    private fun shortTurn(action: String): String {
        val text = action.trim()
        return TURN_WORDS.firstOrNull { text.contains(it) } ?: when {
            text == "左" -> "左"
            text == "右" -> "右"
            else -> ""
        }
    }

    private fun facilityOf(roadType: Int, assistantAction: String, instruction: String): FacilityType {
        val text = assistantAction + instruction
        return when {
            roadType == 1 || text.contains("过马路") || text.contains("人行横道") || text.contains("斑马线") -> FacilityType.Crosswalk
            roadType == 3 || text.contains("天桥") -> FacilityType.Bridge
            roadType == 2 || text.contains("地下通道") || text.contains("地道") -> FacilityType.Tunnel
            roadType == 6 || text.contains("电梯") -> FacilityType.Lift
            roadType == 5 || text.contains("扶梯") -> FacilityType.Escalator
            else -> FacilityType.None
        }
    }
}

/** 搜索 SDK 步行步骤，和导航 SDK 解耦，方便单测。 */
data class SearchWalkStep(
    val instruction: String,
    val orientation: String,
    val road: String,
    val distanceMeters: Int,
    val action: String,
    val assistantAction: String,
    val roadType: Int,
    val trafficLights: Int = 0,
    val polyline: List<GeoPoint> = emptyList(),
)
