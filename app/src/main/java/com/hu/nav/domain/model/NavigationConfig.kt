package com.hu.nav.domain.model

data class NavigationConfig(
    val compassCalibrationEnabled: Boolean = true,
    val calibrationAngleDegrees: Double = 30.0,
    val calibrationHoldMs: Long = 1_500,
    val calibrationTimeoutMs: Long = 20_000,
    val approachingTurnMeters: Int = 30,
    val approachingTurnFarMeters: Int = 80,
    val headingDeviationDegrees: Double = 90.0,
    val headingDeviationClearDegrees: Double = 70.0,
    val headingDeviationHoldMs: Long = 800,
    val headingDeviationRepeatMs: Long = 8_000,
    val offRouteMeters: Double = 25.0,
    val offRouteHoldMs: Long = 2_500,
    val arrivingRemainMeters: Int = 40,
    val arrivingStraightMeters: Double = 40.0,
    val arrivedRemainMeters: Int = 12,
    val arrivedStraightMeters: Double = 18.0,
    val destinationAnnounceIntervalMs: Long = 8_000,
    val speechRate: Float = 1.0f,
)
