package com.hu.nav.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.hu.nav.domain.model.GeoPoint

@Composable
fun BrowseMapView(
    location: GeoPoint?,
    modifier: Modifier = Modifier,
    showMyLocation: Boolean = false,
    recenterToken: Int = 0,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var aMap by remember { mutableStateOf<AMap?>(null) }
    var centered by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        mapView.onCreate(null)
        mapView.onResume()
        val map = mapView.map.apply {
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isZoomGesturesEnabled = true
            uiSettings.isScrollGesturesEnabled = true
        }
        if (showMyLocation) {
            enableMyLocationTriangle(map, followCenter = false)
        } else {
            map.isMyLocationEnabled = false
            map.uiSettings.isMyLocationButtonEnabled = false
        }
        aMap = map
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            map.isMyLocationEnabled = false
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(location, aMap) {
        val map = aMap ?: return@LaunchedEffect
        val loc = location ?: return@LaunchedEffect
        if (centered) return@LaunchedEffect
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.lat, loc.lng), 17f))
        centered = true
    }

    LaunchedEffect(recenterToken) {
        if (recenterToken <= 0) return@LaunchedEffect
        val map = aMap ?: return@LaunchedEffect
        val loc = location ?: return@LaunchedEffect
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.lat, loc.lng), 18f))
        centered = true
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.semantics {
            contentDescription = if (showMyLocation) "地图，正在显示你的当前位置" else "地图"
        },
    )
}

