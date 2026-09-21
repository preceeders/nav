package com.hu.nav.domain.model

data class NaviTick(
    val location: GeoPoint,
    val bearing: Float,
    val accuracy: Float,
    val curStep: Int,
    val curLink: Int,
    val curStepRemainMeters: Int,
    val routeRemainMeters: Int,
    val routeRemainSeconds: Int,
    val currentRoadName: String,
    val nextRoadName: String,
    val iconType: Int,
    val straightToDestMeters: Double,
)
