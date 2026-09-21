package com.hu.nav.ui.route

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hu.nav.data.amap.AMapNaviClient
import com.hu.nav.data.amap.AMapSearchClient
import com.hu.nav.data.amap.PathMapper
import com.hu.nav.data.amap.TransitPathMapper
import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.TravelMode
import com.hu.nav.domain.model.WalkPath
import com.hu.nav.domain.nav.NavigationSession
import com.hu.nav.domain.tts.TtsSpeaker
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class RouteUiState(
    val destinationName: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val paths: List<WalkPath> = emptyList(),
    val selectedIndex: Int = 0,
    val origin: GeoPoint? = null,
    val destinationPoint: GeoPoint? = null,
    val showDetails: Boolean = false,
    val selectedGuideIndex: Int? = null,
    val travelMode: TravelMode = TravelMode.Walk,
)

class RouteViewModel(
    private val naviClient: AMapNaviClient,
    private val search: AMapSearchClient,
    private val session: NavigationSession,
    private val tts: TtsSpeaker,
) : ViewModel() {
    private val _state = MutableStateFlow(
        RouteUiState(
            destinationName = session.destination?.name.orEmpty(),
            origin = session.origin,
            destinationPoint = session.destination?.location,
            travelMode = session.travelMode,
        ),
    )
    val state: StateFlow<RouteUiState> = _state.asStateFlow()

    private var cacheKey: String = ""
    private var cachedWalk: List<WalkPath> = emptyList()
    private var cachedTransit: List<WalkPath> = emptyList()
    private var calcSeq: Int = 0

    init {
        calculate()
    }

    fun setTravelMode(mode: TravelMode) {
        if (_state.value.travelMode == mode && !_state.value.loading) return
        session.travelMode = mode
        _state.update {
            it.copy(travelMode = mode, showDetails = false, selectedGuideIndex = null)
        }
        calculate()
    }

    fun calculate() {
        val origin = session.origin
        val dest = session.destination
        if (origin == null || dest == null) {
            _state.update { it.copy(loading = false, error = "缺少起点或终点") }
            return
        }
        val key = "${origin.lat},${origin.lng}->${dest.location.lat},${dest.location.lng}"
        if (key != cacheKey) {
            cacheKey = key
            cachedWalk = emptyList()
            cachedTransit = emptyList()
        }
        val mode = _state.value.travelMode
        val seq = ++calcSeq
        val cached = if (mode == TravelMode.Transit) cachedTransit else cachedWalk
        if (cached.isNotEmpty()) {
            applyPaths(cached, origin, dest.location, dest.name, mode)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, showDetails = false, selectedGuideIndex = null) }
            val originAddress = session.originAddress.ifBlank {
                runCatching { search.reverseGeocode(origin) }.getOrDefault("")
            }.also { if (it.isNotBlank()) session.originAddress = it }
            if (seq != calcSeq) return@launch
            if (mode == TravelMode.Transit) {
                calculateTransit(origin, dest.location, dest.name, originAddress, seq)
            } else {
                calculateWalk(origin, dest.location, dest.name, originAddress, seq)
            }
        }
    }

    fun select(index: Int) {
        val path = _state.value.paths.getOrNull(index) ?: return
        session.selectedPath = path
        if (!path.isTransit) naviClient.selectRoute(path.id)
        _state.update { it.copy(selectedIndex = index, selectedGuideIndex = null) }
    }

    fun toggleDetails() {
        _state.update {
            val show = !it.showDetails
            it.copy(showDetails = show, selectedGuideIndex = if (show) it.selectedGuideIndex else null)
        }
    }

    fun openDetails(index: Int) {
        select(index)
        _state.update { it.copy(showDetails = true, selectedGuideIndex = null) }
    }

    fun closeDetails() {
        _state.update { it.copy(showDetails = false, selectedGuideIndex = null) }
    }

    fun selectGuide(index: Int) {
        _state.update {
            it.copy(selectedGuideIndex = if (it.selectedGuideIndex == index) null else index)
        }
    }

    fun confirm(): WalkPath? {
        val path = _state.value.paths.getOrNull(_state.value.selectedIndex) ?: return null
        session.selectedPath = path
        session.travelMode = path.mode
        if (!path.isTransit) naviClient.selectRoute(path.id)
        return path
    }

    fun announce() {
        val state = _state.value
        val path = state.paths.getOrNull(state.selectedIndex)
        val dest = state.destinationName.ifBlank { "目的地" }
        val modeName = if (state.travelMode == TravelMode.Transit) "公交" else "步行"
        val text = when {
            state.loading -> "正在规划到${dest}的${modeName}路线"
            state.error != null -> state.error
            path != null -> "目的地$dest。${path.overviewText()}，${path.cardStats()}"
            else -> "没有可用${modeName}路线"
        }
        tts.speak(text.orEmpty(), flush = true)
    }

    private suspend fun calculateWalk(
        origin: GeoPoint,
        dest: GeoPoint,
        destName: String,
        originAddress: String,
        seq: Int,
    ) {
        coroutineScope {
            val naviDeferred = async { naviClient.calculateWalkRoutes(origin, dest) }
            val searchPaths = async {
                runCatching { search.searchWalkPaths(origin, dest) }
                    .onFailure { Log.e(TAG, "walk search failed: ${it.message}", it) }
                    .getOrDefault(emptyList())
            }
            val naviResult = naviDeferred.await()
            val walkDetails = searchPaths.await()
            if (seq != calcSeq) return@coroutineScope
            naviResult.fold(
                onSuccess = { paths ->
                    val enriched = attachSearchDetails(paths, walkDetails, originAddress)
                    cachedWalk = enriched
                    applyPaths(enriched, origin, dest, destName, TravelMode.Walk)
                },
                onFailure = { e ->
                    if (seq != calcSeq) return@fold
                    _state.update { it.copy(loading = false, error = e.message ?: "算路失败") }
                },
            )
        }
    }

    private suspend fun calculateTransit(
        origin: GeoPoint,
        dest: GeoPoint,
        destName: String,
        originAddress: String,
        seq: Int,
    ) {
        val city = session.lastCity.ifBlank {
            runCatching { search.reverseGeocodeDetails(origin).city }.getOrDefault("")
        }.also { if (it.isNotBlank()) session.lastCity = it }
        runCatching { search.searchBusPaths(origin, dest, city) }
            .onFailure { Log.e(TAG, "bus search failed: ${it.message}", it) }
            .fold(
                onSuccess = { busPaths ->
                    if (seq != calcSeq) return
                    val mapped = busPaths.mapIndexed { index, path ->
                        TransitPathMapper.toWalkPath(index, path, originAddress)
                    }.filter { it.transitSegments.isNotEmpty() || it.polyline.size >= 2 }
                    cachedTransit = mapped
                    applyPaths(mapped, origin, dest, destName, TravelMode.Transit)
                },
                onFailure = { e ->
                    if (seq != calcSeq) return
                    _state.update { it.copy(loading = false, error = e.message ?: "公交算路失败") }
                },
            )
    }

    private fun applyPaths(
        paths: List<WalkPath>,
        origin: GeoPoint,
        dest: GeoPoint,
        destName: String,
        mode: TravelMode,
    ) {
        session.paths = paths
        session.selectedPath = paths.firstOrNull()
        session.travelMode = mode
        paths.firstOrNull()?.takeIf { !it.isTransit }?.let { naviClient.selectRoute(it.id) }
        val emptyMsg = if (mode == TravelMode.Transit) "没有可用公交路线" else "没有可用步行路线"
        _state.update {
            it.copy(
                loading = false,
                paths = paths,
                selectedIndex = 0,
                origin = origin,
                destinationPoint = dest,
                destinationName = destName,
                showDetails = false,
                selectedGuideIndex = null,
                travelMode = mode,
                error = if (paths.isEmpty()) emptyMsg else null,
            )
        }
    }

    private fun attachSearchDetails(
        naviPaths: List<WalkPath>,
        searchPaths: List<com.amap.api.services.route.WalkPath>,
        originAddress: String,
    ): List<WalkPath> {
        if (naviPaths.isEmpty()) return emptyList()
        if (searchPaths.isEmpty()) {
            return naviPaths.map { it.copy(originAddress = originAddress) }
        }
        return naviPaths.map { navi ->
            val match = searchPaths.minByOrNull { abs(it.distance - navi.distanceMeters) }
                ?: searchPaths.first()
            PathMapper.withSearchGuides(navi, match, originAddress)
        }
    }

    private companion object {
        const val TAG = "RouteViewModel"
    }
}
