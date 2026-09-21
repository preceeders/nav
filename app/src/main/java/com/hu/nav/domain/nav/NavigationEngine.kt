package com.hu.nav.domain.nav

import com.hu.nav.data.amap.AMapLocationClientWrapper
import com.hu.nav.data.amap.AMapNaviClient
import com.hu.nav.data.amap.NaviSdkEvent
import com.hu.nav.data.sensor.CompassRepository
import com.hu.nav.domain.model.GeoMath
import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.NaviTick
import com.hu.nav.domain.model.NavigationConfig
import com.hu.nav.domain.model.Poi
import com.hu.nav.domain.model.WalkPath
import com.hu.nav.domain.tts.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JourneyUiState(
    val running: Boolean = false,
    val phase: WalkingPhase = WalkingPhase.Idle,
    val destinationName: String = "",
    val remainMeters: Int = 0,
    val remainSeconds: Int = 0,
    val currentRoad: String = "",
    val nextRoad: String = "",
    val muted: Boolean = false,
    val hint: String = "",
    val gpsWeak: Boolean = false,
    val path: WalkPath? = null,
    val origin: GeoPoint? = null,
    val destinationPoint: GeoPoint? = null,
    val headingDegrees: Double? = null,
    val expectedHeadingDegrees: Double? = null,
)

class NavigationEngine(
    private val naviClient: AMapNaviClient,
    private val locationClient: AMapLocationClientWrapper,
    private val compass: CompassRepository,
    private val tts: TtsSpeaker,
    private val scope: CoroutineScope,
    logger: (String) -> Unit = {},
) {
    private val planner = WalkingPlanner(NavigationConfig(), logger)
    private val transit = TransitPlanner()
    private val _state = MutableStateFlow(JourneyUiState())
    val state: StateFlow<JourneyUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<GuideEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<GuideEvent> = _events.asSharedFlow()

    private var collectJob: Job? = null
    private var compassJob: Job? = null
    private var locationJob: Job? = null
    private var lastTick: NaviTick? = null
    private var lastHeading: Double? = null
    private var lastLocation: GeoPoint? = null
    private var destination: Poi? = null
    private var origin: GeoPoint? = null
    private var rerouteInFlight: Boolean = false
    private var transitActive: Boolean = false
    private var fallbackWalk: Boolean = false
    private var ignoreNextRouteSuccess: Boolean = false

    fun updateConfig(config: NavigationConfig) {
        planner.updateConfig(config)
        tts.speechRate = config.speechRate
    }

    fun setMuted(muted: Boolean) {
        tts.isMuted = muted
        _state.update { it.copy(muted = muted) }
        if (muted) tts.stop()
    }

    fun speakCurrentLocation() {
        val tick = lastTick
        val remain = tick?.routeRemainMeters
        val road = tick?.currentRoadName.orEmpty()
        val dest = destination?.name.orEmpty()
        val path = _state.value.path
        val transitHint = if (transitActive) transit.currentHint() else ""
        val guide = if (path != null && remain != null && !transitActive) {
            path.spokenGuides().getOrNull(path.guideIndexForRemain(remain))
        } else {
            null
        }
        val text = buildString {
            if (transitHint.isNotBlank()) {
                append("当前：${transitHint}。")
            } else if (guide != null && guide.instruction.isNotBlank()) {
                append("当前：${guide.instruction}。")
            } else if (road.isNotBlank()) {
                append("当前在$road。")
            }
            val heading = lastHeading
            val expected = planner.expectedHeading
            if (heading != null) append("当前朝向${GeoMath.compassDirection(heading)}。")
            if (expected != null) append("路线朝${GeoMath.compassDirection(expected)}。")
            if (heading != null && expected != null) {
                val diff = GeoMath.angleDiff(heading, expected).toInt()
                if (diff >= 90) append("偏离${diff}度。")
            }
            if (remain != null) append("距离$dest 还有${formatDistance(remain)}。")
            if (isBlank()) append("正在导航到$dest")
        }
        tts.speak(text, flush = true)
    }

    init {
        collectJob = scope.launch { collectSdk() }
    }

    fun start(origin: GeoPoint, destination: Poi, path: WalkPath) {
        compassJob?.cancel()
        locationJob?.cancel()
        planner.stop()
        transit.stop()
        naviClient.stopNavi()
        this.origin = origin
        this.destination = destination
        rerouteInFlight = false
        transitActive = path.isTransit
        fallbackWalk = false
        ignoreNextRouteSuccess = false
        lastTick = null
        lastHeading = null
        lastLocation = origin
        tts.isMuted = false
        _state.value = JourneyUiState(
            running = true,
            phase = if (path.isTransit) WalkingPhase.Guiding else WalkingPhase.Calibrating,
            destinationName = destination.name,
            remainMeters = path.distanceMeters,
            remainSeconds = path.durationSeconds,
            path = path,
            origin = origin,
            destinationPoint = destination.location,
            hint = path.overviewText(),
        )
        compassJob = scope.launch { collectCompass() }
        if (path.isTransit) {
            locationJob = scope.launch { collectLocation() }
            dispatch(transit.start(path, destination.name))
            beginCurrentSegment()
        } else {
            dispatch(planner.start(path, destination.name))
            val started = naviClient.startGpsNavi(path.id)
            if (!started) {
                tts.speak("无法开始导航，请检查定位权限", flush = true)
            }
        }
    }

    fun stop() {
        tts.speak("导航已停止", flush = true)
        stopInternal(keepTts = true)
    }

    private fun stopInternal(keepTts: Boolean) {
        compassJob?.cancel()
        compassJob = null
        locationJob?.cancel()
        locationJob = null
        planner.stop()
        transit.stop()
        naviClient.stopNavi()
        lastTick = null
        lastHeading = null
        lastLocation = null
        rerouteInFlight = false
        transitActive = false
        fallbackWalk = false
        ignoreNextRouteSuccess = false
        if (!keepTts) tts.stop()
        _state.value = JourneyUiState(muted = tts.isMuted)
    }

    private suspend fun collectSdk() {
        naviClient.events.collect { event ->
            when (event) {
                is NaviSdkEvent.Tick -> {
                    if (!_state.value.running) return@collect
                    lastTick = event.tick
                    lastLocation = event.tick.location.takeIf { it.isValid() } ?: lastLocation
                    updateRemainFromTick(event.tick)
                    handleWalkPlannerEvents(planner.onTick(event.tick))
                }
                is NaviSdkEvent.MatchedLocation -> {
                    if (!_state.value.running) return@collect
                    lastLocation = event.point
                    val prev = lastTick
                    if (prev != null) {
                        lastTick = prev.copy(
                            location = event.point,
                            bearing = event.bearing,
                            accuracy = event.accuracy,
                        )
                    }
                }
                NaviSdkEvent.SdkArrived -> {
                    if (_state.value.running) planner.onSdkArrived()
                }
                NaviSdkEvent.SdkYaw -> {
                    if (!_state.value.running || transit.riding) return@collect
                    dispatch(planner.onSdkYaw())
                }
                is NaviSdkEvent.RouteSuccess -> {
                    if (!_state.value.running) return@collect
                    if (ignoreNextRouteSuccess) {
                        ignoreNextRouteSuccess = false
                        return@collect
                    }
                    val path = event.paths.firstOrNull() ?: return@collect
                    rerouteInFlight = false
                    if (transitActive) {
                        val used = overlayTransitWalkGuides(path)
                        dispatch(planner.onNewPath(used))
                        naviClient.startGpsNavi(path.id)
                        return@collect
                    }
                    _state.update {
                        it.copy(
                            path = path,
                            remainMeters = path.distanceMeters,
                            remainSeconds = path.durationSeconds,
                        )
                    }
                    dispatch(planner.onNewPath(path))
                    naviClient.startGpsNavi(path.id)
                }
                is NaviSdkEvent.RouteFailure -> {
                    if (!_state.value.running) return@collect
                    rerouteInFlight = false
                    if (ignoreNextRouteSuccess) {
                        ignoreNextRouteSuccess = false
                        return@collect
                    }
                    tts.speak("重新规划失败，请停止后重试", flush = true)
                }
                is NaviSdkEvent.GpsWeak -> _state.update { it.copy(gpsWeak = event.weak) }
                else -> Unit
            }
        }
    }

    private suspend fun collectCompass() {
        compass.headings().collect { heading ->
            lastHeading = heading.toDouble()
            _state.update {
                it.copy(
                    headingDegrees = lastHeading,
                    expectedHeadingDegrees = if (transit.riding) null else planner.expectedHeading,
                )
            }
            if (transit.riding) return@collect
            handleWalkPlannerEvents(planner.onCompass(heading.toDouble(), lastTick))
        }
    }

    private suspend fun collectLocation() {
        locationClient.locations().collect { loc ->
            if (!_state.value.running || !transitActive) return@collect
            lastLocation = loc.point
            if (loc.bearing > 0f) lastHeading = loc.bearing.toDouble()
            publishTransitRemain(loc.point)
            if (transit.riding) {
                val before = transit.segmentIndex
                finishTransitEvents(transit.onRideLocation(loc.point), before)
            } else if (fallbackWalk) {
                emitSyntheticWalkTick(loc.point)
            }
        }
    }

    private fun dispatch(events: List<GuideEvent>) {
        events.forEach { event ->
            _events.tryEmit(event)
            val hint = if (transitActive && event !is GuideEvent.Arrived) {
                transit.currentHint().ifBlank { hintOf(event) }
            } else {
                hintOf(event)
            }
            _state.update { it.copy(phase = planner.phase, hint = hint) }
            speak(event)
            if (event is GuideEvent.Reroute) requestReroute()
            if (event is GuideEvent.Arrived) {
                naviClient.stopNavi()
                _state.update { it.copy(running = false, hint = "已到达") }
            }
        }
    }

    private fun requestReroute() {
        if (rerouteInFlight) return
        val from = lastTick?.location ?: lastLocation ?: origin ?: return
        val to = if (transitActive) {
            transit.currentWalkTargetPoint() ?: return
        } else {
            destination?.location ?: return
        }
        rerouteInFlight = true
        scope.launch {
            naviClient.calculateWalkRoutes(from, to)
        }
    }

    private fun beginCurrentSegment() {
        if (!_state.value.running) return
        val segment = transit.currentSegment ?: run {
            dispatch(listOf(GuideEvent.Arrived(destination?.name.orEmpty())))
            return
        }
        fallbackWalk = false
        if (segment.isRide) {
            naviClient.stopNavi()
            planner.stop()
            _state.update {
                it.copy(
                    phase = WalkingPhase.Guiding,
                    hint = segment.instruction,
                    expectedHeadingDegrees = null,
                )
            }
            return
        }
        val target = transit.currentWalkTargetPoint()
        val from = lastLocation ?: origin
        if (target == null || from == null) {
            handleTransitWalkArrived()
            return
        }
        if (segment.distanceMeters in 1..24 && GeoMath.distanceMeters(from, target) < 30) {
            handleTransitWalkArrived()
            return
        }
        ignoreNextRouteSuccess = true
        scope.launch {
            val result = naviClient.calculateWalkRoutes(from, target)
            if (!_state.value.running) return@launch
            result.fold(
                onSuccess = { paths ->
                    val naviPath = paths.firstOrNull()
                    if (naviPath == null) {
                        startFallbackWalk()
                        return@fold
                    }
                    val used = overlayTransitWalkGuides(naviPath)
                    dispatch(planner.start(used, transit.currentWalkTargetName()))
                    val started = naviClient.startGpsNavi(naviPath.id)
                    if (!started) startFallbackWalk()
                },
                onFailure = { startFallbackWalk() },
            )
        }
    }

    private fun startFallbackWalk() {
        val walk = transit.currentWalkPath() ?: run {
            tts.speak("无法规划到车站的步行路线", flush = true)
            handleTransitWalkArrived()
            return
        }
        fallbackWalk = true
        dispatch(planner.start(walk, transit.currentWalkTargetName()))
        lastLocation?.let { emitSyntheticWalkTick(it) }
    }

    private fun overlayTransitWalkGuides(naviPath: WalkPath): WalkPath {
        val local = transit.currentWalkPath() ?: return naviPath
        return if (local.spokenGuides().isNotEmpty()) {
            naviPath.copy(guides = local.guides)
        } else {
            naviPath
        }
    }

    private fun handleWalkPlannerEvents(events: List<GuideEvent>) {
        if (transitActive && events.any { it is GuideEvent.Arrived }) {
            dispatch(events.filter { it !is GuideEvent.Arrived })
            handleTransitWalkArrived()
            return
        }
        dispatch(events)
    }

    private fun handleTransitWalkArrived() {
        naviClient.stopNavi()
        fallbackWalk = false
        val before = transit.segmentIndex
        finishTransitEvents(transit.onWalkArrived(), before)
    }

    private fun finishTransitEvents(events: List<GuideEvent>, indexBefore: Int) {
        val arrived = events.any { it is GuideEvent.Arrived }
        dispatch(events)
        if (arrived || !_state.value.running) return
        if (transit.segmentIndex != indexBefore) {
            beginCurrentSegment()
        }
    }

    private fun emitSyntheticWalkTick(point: GeoPoint) {
        val walk = transit.currentWalkPath() ?: return
        val dest = transit.currentWalkTargetPoint() ?: return
        val line = walk.displayPolyline().ifEmpty { walk.polyline }
        if (line.size < 2) return
        val remain = GeoMath.remainAlongPolyline(point, line).toInt()
        val tick = NaviTick(
            location = point,
            bearing = lastHeading?.toFloat() ?: 0f,
            accuracy = 0f,
            curStep = 0,
            curLink = 0,
            curStepRemainMeters = remain,
            routeRemainMeters = remain,
            routeRemainSeconds = walk.durationSeconds,
            currentRoadName = walk.spokenGuides().firstOrNull()?.roadName.orEmpty(),
            nextRoadName = "",
            iconType = 10,
            straightToDestMeters = GeoMath.distanceMeters(point, dest),
        )
        lastTick = tick
        handleWalkPlannerEvents(planner.onTick(tick))
    }

    private fun updateRemainFromTick(tick: NaviTick) {
        if (transitActive) {
            publishTransitRemain(tick.location)
        } else {
            _state.update {
                it.copy(
                    remainMeters = tick.routeRemainMeters,
                    remainSeconds = tick.routeRemainSeconds,
                    currentRoad = tick.currentRoadName,
                    nextRoad = tick.nextRoadName,
                )
            }
        }
    }

    private fun publishTransitRemain(location: GeoPoint?) {
        _state.update {
            it.copy(
                remainMeters = transit.remainMeters(location),
                remainSeconds = transit.remainSeconds(location),
                currentRoad = transit.currentHint(),
                hint = transit.currentHint().ifBlank { it.hint },
            )
        }
    }

    private fun speak(event: GuideEvent) {
        val text = when (event) {
            is GuideEvent.CalibrationStart -> "请将身体朝向${event.expectedDirection}，开始校准方向"
            is GuideEvent.CalibrationComplete -> "方向已校准，开始导航"
            GuideEvent.CalibrationTimeout -> "罗盘校准超时，按路线距离继续导航"
            is GuideEvent.ApproachingTurn -> buildString {
                append("前方${event.distanceMeters}米，${event.instruction}")
            }
            is GuideEvent.TurnEntered -> event.instruction.ifBlank { "请转弯" }
            is GuideEvent.TurnEnded ->
                if (event.nextRoadName.isNotBlank()) "转弯结束，进入${event.nextRoadName}" else "转弯结束"
            is GuideEvent.Crosswalk ->
                if (event.nextRoadName.isNotBlank()) "前方过马路，过街后进入${event.nextRoadName}" else "前方过马路"
            is GuideEvent.HeadingDeviation -> {
                val dir = event.expectedDirection.ifBlank { "路线方向" }
                when {
                    event.angleDiff >= 150 -> "朝向反了，请掉头，朝${dir}走"
                    event.turnLeft -> "朝向偏了，请向左转，朝${dir}走"
                    else -> "朝向偏了，请向右转，朝${dir}走"
                }
            }
            GuideEvent.OffRoute -> "已偏航，正在重新规划路线"
            GuideEvent.Reroute -> ""
            is GuideEvent.ApproachingDestination ->
                "接近目的地，${event.direction}方向约${event.distanceMeters}米"
            is GuideEvent.Arrived -> "已到达${event.destinationName}"
            is GuideEvent.TransitPrompt -> event.text
        }
        if (text.isNotBlank()) tts.speak(text, flush = true)
    }

    private fun hintOf(event: GuideEvent): String = when (event) {
        is GuideEvent.CalibrationStart -> "校准朝向：${event.expectedDirection}"
        is GuideEvent.CalibrationComplete -> "导航中"
        GuideEvent.CalibrationTimeout -> "导航中"
        is GuideEvent.ApproachingTurn -> event.instruction
        is GuideEvent.TurnEntered -> event.instruction
        is GuideEvent.TurnEnded -> event.nextRoadName
        is GuideEvent.Crosswalk -> "过马路"
        is GuideEvent.HeadingDeviation ->
            if (event.expectedDirection.isNotBlank()) "朝向偏差，应走${event.expectedDirection}" else "方向偏差"
        GuideEvent.OffRoute -> "偏航"
        GuideEvent.Reroute -> "重新规划"
        is GuideEvent.ApproachingDestination -> "接近目的地 ${event.distanceMeters}米"
        is GuideEvent.Arrived -> "已到达"
        is GuideEvent.TransitPrompt -> event.text
    }

    private fun formatDistance(meters: Int): String {
        return if (meters >= 1000) String.format("%.1f公里", meters / 1000.0) else "${meters}米"
    }
}
