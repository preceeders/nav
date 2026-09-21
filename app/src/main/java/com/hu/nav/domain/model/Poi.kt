package com.hu.nav.domain.model

data class Poi(
    val id: String,
    val name: String,
    val address: String,
    val location: GeoPoint,
    val distanceMeters: Int? = null,
)
