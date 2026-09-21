package com.hu.nav.domain.nav

import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.Poi
import com.hu.nav.domain.model.TravelMode
import com.hu.nav.domain.model.WalkPath

class NavigationSession {
    var origin: GeoPoint? = null
    var originAddress: String = ""
    var destination: Poi? = null
    var paths: List<WalkPath> = emptyList()
    var selectedPath: WalkPath? = null
    var lastCity: String = ""
    var travelMode: TravelMode = TravelMode.Walk

    fun clearJourney() {
        selectedPath = null
        paths = emptyList()
    }

    fun reset() {
        origin = null
        originAddress = ""
        destination = null
        paths = emptyList()
        selectedPath = null
        lastCity = ""
        travelMode = TravelMode.Walk
    }
}
