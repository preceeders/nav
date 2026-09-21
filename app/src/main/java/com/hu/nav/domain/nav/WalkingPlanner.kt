package com.hu.nav.domain.nav

import com.hu.nav.domain.model.FacilityType
import com.hu.nav.domain.model.GeoMath
import com.hu.nav.domain.model.NaviTick
import com.hu.nav.domain.model.NavigationConfig
import com.hu.nav.domain.model.WalkPath

/**
 * 无障碍步行引导状态机。高德 SDK 的到达/语音只当输入，不直接对用户播报。
 */
class WalkingPlanner(
    private var config: NavigationConfig,
    private val logger: (String) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    var phase: WalkingPhase = WalkingPhase.Idle
        private set

    private var path: WalkPath? = null
    private var destinationName: String = ""
    var expectedHeading: Double? = null
        private set
    private var expectedBearing: Double? = null
    private var alignedSince: Long? = null
    private var calibratingSince: Long? = null
    private var headingDeviatingSince: Long? = null
    private var lastHeadingRemindAt: Long = 0L
    private var offRouteSince: Long? = null
    private var lastAnnouncedStep: Int = -1
    private var lastAnnouncedGuide: Int = -1
    private var lastEnteredGuide: Int = -1
    private var lastSpokenCurrentGuide: Int = -1
    private var lastTurnEnteredStep: Int = -1
    private var lastCrosswalkStep: Int = -1
    private var lastDestAnnounceAt: Long = 0L
    private var approachingTurnSpoken: Boolean = false
    private var inTurn: Boolean = false
    private var sdkArrivedSeen: Boolean = false

    fun updateConfig(config: NavigationConfig) {
        this.config = config
    }

    fun start(path: WalkPath, destinationName: String): List<GuideEvent> {
        stop()
        this.path = path
        this.destinationName = destinationName
        expectedBearing = path.expectedBearingAt(0, 0)
        expectedHeading = expectedBearing
        val events = mutableListOf<GuideEvent>()
        if (!config.compassCalibrationEnabled) {
            phase = WalkingPhase.Guiding
            logger("calibration skipped (disabled), expectedBearing=$expectedBearing")
            events += GuideEvent.CalibrationComplete(expectedBearing ?: 0.0)
            events += speakFirstDetailGuide()
        } else {
            phase = WalkingPhase.Calibrating
            calibratingSince = clock()
            val dir = expectedBearing?.let { GeoMath.compassDirection(it) } ?: "前方"
            logger("calibration started, direction=$dir")
            events += GuideEvent.CalibrationStart(dir)
        }
        return events
    }

    fun stop() {
        phase = WalkingPhase.Idle
        path = null
        expectedBearing = null
        expectedHeading = null
        alignedSince = null
        calibratingSince = null
        headingDeviatingSince = null
        lastHeadingRemindAt = 0L
        offRouteSince = null
        lastAnnouncedStep = -1
        lastTurnEnteredStep = -1
        lastCrosswalkStep = -1
        lastAnnouncedGuide = -1
        lastEnteredGuide = -1
        lastSpokenCurrentGuide = -1
        lastDestAnnounceAt = 0L
        approachingTurnSpoken = false
        inTurn = false
        sdkArrivedSeen = false
        logger("deactivated")
    }

    fun onNewPath(path: WalkPath): List<GuideEvent> {
        logger("segment changed / new path length=${path.distanceMeters}")
        this.path = path
        expectedBearing = path.expectedBearingAt(0, 0)
        expectedHeading = expectedBearing
        lastAnnouncedStep = -1
        lastTurnEnteredStep = -1
        lastCrosswalkStep = -1
        lastAnnouncedGuide = -1
        lastEnteredGuide = -1
        lastSpokenCurrentGuide = -1
        approachingTurnSpoken = false
        inTurn = false
        sdkArrivedSeen = false
        headingDeviatingSince = null
        lastHeadingRemindAt = 0L
        offRouteSince = null
        return if (config.compassCalibrationEnabled) {
            phase = WalkingPhase.Calibrating
            calibratingSince = clock()
            alignedSince = null
            val dir = expectedBearing?.let { GeoMath.compassDirection(it) } ?: "前方"
            logger("calibration started, direction=$dir")
            listOf(GuideEvent.CalibrationStart(dir))
        } else {
            phase = WalkingPhase.Guiding
            emptyList()
        }
    }

    fun onSdkYaw(): List<GuideEvent> {
        if (phase == WalkingPhase.Idle || phase == WalkingPhase.Arrived) return emptyList()
        logger("off-route detected (phase=$phase) sdk yaw")
        phase = WalkingPhase.Rerouting
        return listOf(GuideEvent.OffRoute)
    }

    /** SDK 到达只记标记，真正到达由距离双条件确认。 */
    fun onSdkArrived() {
        sdkArrivedSeen = true
        logger("onSdkArrived: force=false, waiting planner distance")
    }

    fun onCompass(heading: Double, tick: NaviTick?): List<GuideEvent> {
        if (phase == WalkingPhase.Idle || phase == WalkingPhase.Arrived) return emptyList()
        val expected = currentExpectedBearing(tick) ?: expectedBearing ?: return emptyList()
        expectedBearing = expected
        expectedHeading = expected
        val diff = GeoMath.angleDiff(heading, expected)
        val now = clock()
        val events = mutableListOf<GuideEvent>()

        when (phase) {
            WalkingPhase.Calibrating -> {
                if (diff <= config.calibrationAngleDegrees) {
                    if (alignedSince == null) alignedSince = now
                    if (now - (alignedSince ?: now) >= config.calibrationHoldMs) {
                        phase = WalkingPhase.Guiding
                        logger("calibration complete via compass (heading=$heading)")
                        events += GuideEvent.CalibrationComplete(heading)
                        events += speakFirstDetailGuide()
                    }
                } else {
                    alignedSince = null
                    val started = calibratingSince ?: now
                    if (now - started >= config.calibrationTimeoutMs) {
                        phase = WalkingPhase.Guiding
                        logger("calibration complete (timeout), continue by route distance")
                        events += GuideEvent.CalibrationTimeout
                    }
                }
            }
            WalkingPhase.Guiding, WalkingPhase.Arriving -> {
                events += emitHeadingReminder(heading, expected, diff, tick, now)
            }
            else -> Unit
        }
        return events
    }

    fun onTick(tick: NaviTick): List<GuideEvent> {
        val currentPath = path ?: return emptyList()
        if (phase == WalkingPhase.Idle || phase == WalkingPhase.Arrived || phase == WalkingPhase.Rerouting) {
            return emptyList()
        }
        val events = mutableListOf<GuideEvent>()
        val now = clock()
        expectedBearing = currentExpectedBearing(tick) ?: expectedBearing

        if (phase == WalkingPhase.Calibrating) {
            val started = calibratingSince ?: now
            if (now - started >= config.calibrationTimeoutMs) {
                phase = WalkingPhase.Guiding
                logger("calibration complete (timeout on tick)")
                events += GuideEvent.CalibrationTimeout
                events += speakFirstDetailGuide()
            } else {
                logger("compass update ignored until calibrated")
                return events
            }
        }

        val polylineDist = GeoMath.distanceToPolyline(tick.location, currentPath.polyline)
        if (polylineDist > config.offRouteMeters) {
            if (offRouteSince == null) offRouteSince = now
            if (now - (offRouteSince ?: now) >= config.offRouteHoldMs) {
                logger("off-route detected (phase=$phase) dist=$polylineDist")
                events += enterOffRoute()
                return events
            }
        } else {
            offRouteSince = null
        }

        val arrived = tick.routeRemainMeters <= config.arrivedRemainMeters &&
            tick.straightToDestMeters <= config.arrivedStraightMeters
        if (arrived) {
            phase = WalkingPhase.Arrived
            logger("planner confirmed arrival")
            events += GuideEvent.Arrived(destinationName)
            return events
        }

        val approaching = tick.routeRemainMeters <= config.arrivingRemainMeters &&
            tick.straightToDestMeters <= config.arrivingStraightMeters
        if (approaching) {
            if (phase != WalkingPhase.Arriving) {
                phase = WalkingPhase.Arriving
                logger("stage arriving remain=${tick.routeRemainMeters}")
            }
            if (now - lastDestAnnounceAt >= config.destinationAnnounceIntervalMs) {
                lastDestAnnounceAt = now
                val dir = GeoMath.compassDirection(tick.bearing.toDouble())
                events += GuideEvent.ApproachingDestination(dir, tick.routeRemainMeters)
            }
            return events
        }

        events += emitProgress(currentPath, tick)
        return events
    }

    private fun speakFirstDetailGuide(): List<GuideEvent> {
        val first = path?.spokenGuides()?.firstOrNull() ?: return emptyList()
        lastSpokenCurrentGuide = 0
        logger("detail current guide=0 ${first.instruction}")
        return listOf(GuideEvent.TurnEntered(first.instruction))
    }

    private fun emitProgress(path: WalkPath, tick: NaviTick): List<GuideEvent> {
        return if (path.spokenGuides().isNotEmpty()) {
            emitFromDetailGuides(path, tick)
        } else {
            emitTurnAndFacility(path, tick)
        }
    }

    private fun emitFromDetailGuides(path: WalkPath, tick: NaviTick): List<GuideEvent> {
        val guides = path.spokenGuides()
        val index = path.guideIndexForRemain(tick.routeRemainMeters)
        if (index < 0) return emptyList()
        val current = guides[index]
        val next = guides.getOrNull(index + 1)
        val remainHere = path.remainInGuide(tick.routeRemainMeters)
        val events = mutableListOf<GuideEvent>()

        if (lastSpokenCurrentGuide != index && lastEnteredGuide != index) {
            lastSpokenCurrentGuide = index
            logger("detail current guide=$index ${current.instruction}")
            events += GuideEvent.TurnEntered(current.instruction)
        }

        val approachAt = minOf(
            config.approachingTurnFarMeters,
            (current.lengthMeters / 2).coerceAtLeast(config.approachingTurnMeters),
        )
        if (next != null &&
            remainHere <= approachAt &&
            lastAnnouncedGuide != index + 1
        ) {
            lastAnnouncedGuide = index + 1
            logger("detail approaching guide=${index + 1} remain=$remainHere ${next.instruction}")
            events += GuideEvent.ApproachingTurn(
                instruction = next.instruction,
                distanceMeters = remainHere.coerceAtLeast(1),
                nextRoadName = next.roadName,
            )
        }

        if (next != null &&
            remainHere <= config.approachingTurnMeters &&
            lastEnteredGuide != index + 1
        ) {
            lastEnteredGuide = index + 1
            logger("detail entered guide=${index + 1} ${next.instruction}")
            events += GuideEvent.TurnEntered(next.instruction)
        }
        return events
    }

    private fun emitTurnAndFacility(path: WalkPath, tick: NaviTick): List<GuideEvent> {
        val events = mutableListOf<GuideEvent>()
        val step = path.steps.getOrNull(tick.curStep)
        val nextStep = path.steps.getOrNull(tick.curStep + 1)
        val link = path.linkAt(tick.curStep, tick.curLink)

        if (step != null && step.isTurn && tick.curStep != lastTurnEnteredStep) {
            if (tick.curStepRemainMeters <= config.approachingTurnMeters && !inTurn) {
                inTurn = true
                lastTurnEnteredStep = tick.curStep
                logger("linkTurnEntered (boundary) step=${tick.curStep}")
                events += GuideEvent.TurnEntered(step.instruction.ifBlank { "转弯" })
                approachingTurnSpoken = false
            } else if (
                tick.curStepRemainMeters <= config.approachingTurnFarMeters &&
                !approachingTurnSpoken &&
                tick.curStep != lastAnnouncedStep
            ) {
                approachingTurnSpoken = true
                lastAnnouncedStep = tick.curStep
                logger("approachingLinkTurn (curve self) step=${tick.curStep}")
                events += GuideEvent.ApproachingTurn(
                    instruction = step.instruction.ifBlank { "转弯" },
                    distanceMeters = tick.curStepRemainMeters,
                    nextRoadName = tick.nextRoadName.ifBlank { nextStep?.roadName.orEmpty() },
                )
            }
        } else if (inTurn && (step == null || !step.isTurn || tick.curStepRemainMeters <= 5)) {
            inTurn = false
            approachingTurnSpoken = false
            val road = tick.currentRoadName.ifBlank { nextStep?.roadName.orEmpty() }
            logger("linkTurnEnded (curve self) step=${tick.curStep}")
            events += GuideEvent.TurnEnded(road)
        }

        val facility = link?.facility ?: FacilityType.None
        val instructionHint = step?.instruction.orEmpty()
        val isCrosswalk = facility == FacilityType.Crosswalk ||
            instructionHint.contains("过马路") ||
            instructionHint.contains("人行横道") ||
            instructionHint.contains("过街")
        if (isCrosswalk && tick.curStep != lastCrosswalkStep && tick.curStepRemainMeters <= 40) {
            lastCrosswalkStep = tick.curStep
            val nextRoad = tick.nextRoadName.ifBlank { nextStep?.roadName.orEmpty() }
            logger("facility crosswalk step=${tick.curStep}")
            events += GuideEvent.Crosswalk(nextRoad)
        }
        return events
    }

    private fun emitHeadingReminder(
        heading: Double,
        expected: Double,
        diff: Double,
        tick: NaviTick?,
        now: Long,
    ): List<GuideEvent> {
        if (diff < config.headingDeviationClearDegrees) {
            headingDeviatingSince = null
            return emptyList()
        }
        if (diff <= config.headingDeviationDegrees) return emptyList()
        if (isNearTurn(tick)) {
            headingDeviatingSince = null
            return emptyList()
        }
        if (headingDeviatingSince == null) headingDeviatingSince = now
        val held = now - (headingDeviatingSince ?: now) >= config.headingDeviationHoldMs
        val due = lastHeadingRemindAt == 0L ||
            now - lastHeadingRemindAt >= config.headingDeviationRepeatMs
        if (!held || !due) return emptyList()
        lastHeadingRemindAt = now
        val signed = GeoMath.signedAngleDiff(heading, expected)
        val turnLeft = signed < 0
        val dir = GeoMath.compassDirection(expected)
        logger("heading remind diff=$diff signed=$signed expected=$expected heading=$heading")
        return listOf(
            GuideEvent.HeadingDeviation(
                angleDiff = diff,
                turnLeft = turnLeft,
                expectedDirection = dir,
            ),
        )
    }

    private fun isNearTurn(tick: NaviTick?): Boolean {
        if (inTurn) return true
        if (tick == null) return false
        if (tick.curStepRemainMeters > config.approachingTurnMeters) return false
        val step = path?.steps?.getOrNull(tick.curStep)
        val next = path?.steps?.getOrNull(tick.curStep + 1)
        return step?.isTurn == true || next?.isTurn == true
    }

    private fun enterOffRoute(): List<GuideEvent> {
        phase = WalkingPhase.OffRoute
        headingDeviatingSince = null
        offRouteSince = null
        return listOf(GuideEvent.OffRoute, GuideEvent.Reroute)
    }

    private fun currentExpectedBearing(tick: NaviTick?): Double? {
        val p = path ?: return expectedBearing
        if (tick == null) return expectedBearing
        return p.expectedBearingAt(tick.curStep, tick.curLink, tick.location) ?: expectedBearing
    }
}
