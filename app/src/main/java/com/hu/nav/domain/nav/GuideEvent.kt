package com.hu.nav.domain.nav

sealed class GuideEvent {
    data class CalibrationStart(val expectedDirection: String) : GuideEvent()
    data class CalibrationComplete(val heading: Double) : GuideEvent()
    data object CalibrationTimeout : GuideEvent()
    data class ApproachingTurn(
        val instruction: String,
        val distanceMeters: Int,
        val nextRoadName: String,
    ) : GuideEvent()
    data class TurnEntered(val instruction: String) : GuideEvent()
    data class TurnEnded(val nextRoadName: String) : GuideEvent()
    data class Crosswalk(val nextRoadName: String) : GuideEvent()
    data class HeadingDeviation(
        val angleDiff: Double,
        val turnLeft: Boolean = false,
        val expectedDirection: String = "",
    ) : GuideEvent()
    data object OffRoute : GuideEvent()
    data object Reroute : GuideEvent()
    data class ApproachingDestination(
        val direction: String,
        val distanceMeters: Int,
    ) : GuideEvent()
    data class Arrived(val destinationName: String) : GuideEvent()
    data class TransitPrompt(val text: String) : GuideEvent()
}

enum class WalkingPhase {
    Idle,
    Calibrating,
    Guiding,
    OffRoute,
    Rerouting,
    Arriving,
    Arrived,
}
