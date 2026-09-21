package com.hu.nav.ui.map

import android.graphics.Color
import android.view.View
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
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.WalkPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.min

@Composable
fun RoutePreviewMap(
    paths: List<WalkPath>,
    selectedIndex: Int,
    origin: GeoPoint?,
    destination: GeoPoint?,
    destinationName: String,
    showMyLocation: Boolean = false,
    focusedPolyline: List<GeoPoint> = emptyList(),
    focusedLabel: String = "",
    followMyLocation: Boolean = false,
    fitToRoute: Boolean = true,
    showOriginMarker: Boolean = true,
    showDestinationWindow: Boolean = true,
    showSystemCompass: Boolean = true,
    recenterToken: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var aMap by remember { mutableStateOf<AMap?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(0 to 0) }

    var didFollowZoom by remember { mutableStateOf(false) }

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
            uiSettings.isCompassEnabled = showSystemCompass
            uiSettings.isZoomGesturesEnabled = true
            uiSettings.isScrollGesturesEnabled = true
            setOnMapLoadedListener { mapReady = true }
        }
        aMap = map
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(
        aMap,
        mapReady,
        viewSize,
        paths,
        selectedIndex,
        origin,
        destination,
        showMyLocation,
        focusedPolyline,
        focusedLabel,
        followMyLocation,
        fitToRoute,
        showOriginMarker,
        showDestinationWindow,
        showSystemCompass,
    ) {
        val map = aMap ?: return@LaunchedEffect
        map.uiSettings.isCompassEnabled = showSystemCompass
        val camera = drawRoutes(
            map = map,
            paths = paths,
            selectedIndex = selectedIndex,
            origin = origin,
            destination = destination,
            destinationName = destinationName,
            focusedPolyline = focusedPolyline,
            focusedLabel = focusedLabel,
            showOriginMarker = showOriginMarker,
            showDestinationWindow = showDestinationWindow,
        )
        if (showMyLocation) {
            enableMyLocationTriangle(
                map = map,
                followCenter = followMyLocation,
                iconColor = if (followMyLocation) "#4FC3F7" else "#1B5E20",
            )
        }
        mapView.awaitLaidOut()
        if (followMyLocation) {
            if (!didFollowZoom && origin != null && origin.isValid()) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(origin.lat, origin.lng), 17f))
                didFollowZoom = true
            }
            return@LaunchedEffect
        }
        if (!fitToRoute) return@LaunchedEffect
        if (camera == null) return@LaunchedEffect
        if (mapReady) {
            fitBounds(map, mapView, camera)
            delay(280)
            fitBounds(map, mapView, camera)
        }
    }

    LaunchedEffect(recenterToken, aMap, origin) {
        if (recenterToken <= 0) return@LaunchedEffect
        val map = aMap ?: return@LaunchedEffect
        val mine = map.myLocation?.let { LatLng(it.latitude, it.longitude) }
            ?: origin?.takeIf { it.isValid() }?.let { LatLng(it.lat, it.lng) }
            ?: return@LaunchedEffect
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(mine, 17f))
    }

    val selected = paths.getOrNull(selectedIndex)
    val desc = when {
        focusedLabel.isNotBlank() -> "路线地图，正在显示：${focusedLabel}"
        selected != null -> "完整路线预览，从当前位置到$destinationName"
        else -> "路线地图"
    }
    AndroidView(
        factory = { mapView },
        update = { view ->
            val size = view.width to view.height
            if (view.width > 0 && view.height > 0 && size != viewSize) {
                viewSize = size
            }
        },
        modifier = modifier.semantics { contentDescription = desc },
    )
}

private fun drawRoutes(
    map: AMap,
    paths: List<WalkPath>,
    selectedIndex: Int,
    origin: GeoPoint?,
    destination: GeoPoint?,
    destinationName: String,
    focusedPolyline: List<GeoPoint>,
    focusedLabel: String,
    showOriginMarker: Boolean = true,
    showDestinationWindow: Boolean = true,
): LatLngBounds? {
    map.clear()
    val previewPoints = mutableListOf<LatLng>()
    paths.forEachIndexed { index, path ->
        val points = pathLatLngs(path, origin, destination)
        if (points.size < 2) return@forEachIndexed
        val selected = index == selectedIndex
        if (selected) previewPoints += points
        val focusing = selected && focusedPolyline.size >= 2
        map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(if (selected) 18f else 10f)
                .color(
                    when {
                        focusing -> Color.parseColor("#81C784")
                        selected -> Color.parseColor("#1B5E20")
                        else -> Color.parseColor("#889E9E9E")
                    },
                )
                .zIndex(if (selected) 2f else 1f),
        )
    }
    origin?.takeIf { showOriginMarker && it.isValid() }?.let {
        val latLng = LatLng(it.lat, it.lng)
        previewPoints += latLng
        map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("我的位置")
                .snippet("起点")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)),
        )
    }
    val destLatLng = destination?.takeIf { it.isValid() }?.let { LatLng(it.lat, it.lng) }
    destLatLng?.let { latLng ->
        previewPoints += latLng
        val marker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(destinationName.ifBlank { "目的地" })
                .snippet("终点")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
        )
        if (showDestinationWindow) marker?.showInfoWindow()
    }
    val focusPoints = focusedPolyline.map { LatLng(it.lat, it.lng) }.filter { it.isUsable() }
    if (focusPoints.size >= 2) {
        map.addPolyline(
            PolylineOptions()
                .addAll(focusPoints)
                .width(22f)
                .color(Color.parseColor("#0D47A1"))
                .zIndex(4f),
        )
        val mid = focusPoints[focusPoints.size / 2]
        map.addMarker(
            MarkerOptions()
                .position(mid)
                .title(focusedLabel.ifBlank { "当前路段" })
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)),
        )
        return boundsOf(focusPoints)
    }
    return boundsOf(previewPoints)
}

private fun pathLatLngs(
    path: WalkPath,
    origin: GeoPoint?,
    destination: GeoPoint?,
): List<LatLng> {
    val line = path.displayPolyline().map { LatLng(it.lat, it.lng) }.filter { it.isUsable() }
    val start = origin?.takeIf { it.isValid() }?.let { LatLng(it.lat, it.lng) }
    val end = destination?.takeIf { it.isValid() }?.let { LatLng(it.lat, it.lng) }
    if (line.size >= 2) {
        val out = line.toMutableList()
        if (start != null && out.first() != start) out.add(0, start)
        if (end != null && out.last() != end) out.add(end)
        return out
    }
    return listOfNotNull(start, end)
}

private fun LatLng.isUsable(): Boolean {
    return latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        !(latitude == 0.0 && longitude == 0.0)
}

private fun boundsOf(points: List<LatLng>): LatLngBounds? {
    val usable = points.filter { it.isUsable() }
    if (usable.isEmpty()) return null
    val builder = LatLngBounds.Builder()
    usable.forEach { builder.include(it) }
    return runCatching { builder.build() }.getOrNull()
}

private fun fitBounds(map: AMap, mapView: MapView, bounds: LatLngBounds) {
    val width = mapView.width
    val height = mapView.height
    if (width < 16 || height < 16) {
        mapView.post { fitBounds(map, mapView, bounds) }
        return
    }
    val padding = (min(width, height) * 0.18f).toInt().coerceIn(64, 180)
    val sized = CameraUpdateFactory.newLatLngBounds(bounds, width, height, padding)
    val fallback = CameraUpdateFactory.newLatLngBounds(bounds, padding)
    runCatching { map.moveCamera(sized) }
        .recoverCatching { map.moveCamera(fallback) }
        .recoverCatching { map.animateCamera(fallback) }
}

private suspend fun View.awaitLaidOut() {
    if (isLaidOut && width > 0 && height > 0) return
    suspendCancellableCoroutine { cont ->
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (v.width > 0 && v.height > 0 && cont.isActive) {
                    v.removeOnLayoutChangeListener(this)
                    cont.resume(Unit)
                }
            }
        }
        addOnLayoutChangeListener(listener)
        cont.invokeOnCancellation { removeOnLayoutChangeListener(listener) }
        post {
            if (isLaidOut && width > 0 && height > 0 && cont.isActive) {
                removeOnLayoutChangeListener(listener)
                cont.resume(Unit)
            }
        }
    }
}
