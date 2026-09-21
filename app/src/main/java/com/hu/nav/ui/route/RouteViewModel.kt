package com.hu.nav.ui.route

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hu.nav.data.amap.AMapNaviClient
import com.hu.nav.data.amap.AMapSearchClient
import com.hu.nav.data.amap.PathMapper
import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.WalkPath
import com.hu.nav.domain.nav.NavigationSession
import com.hu.nav.domain.tts.TtsSpeaker
import kotlinx.coroutines.async
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
        ),
    )
    val state: StateFlow<RouteUiState> = _state.asStateFlow()

    init {
        calculate()
    }

    fun calculate() {
        val origin = session.origin
        val dest = session.destination
        if (origin == null || dest == null) {
            _state.update { it.copy(loading = false, error = "缺少起点或终点") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val originAddress = session.originAddress.ifBlank {
                runCatching { search.reverseGeocode(origin) }.getOrDefault("")
            }.also { if (it.isNotBlank()) session.originAddress = it }

            val naviDeferred = async { naviClient.calculateWalkRoutes(origin, dest.location) }
            val searchPaths = async {
                runCatching { search.searchWalkPaths(origin, dest.location) }
                    .onFailure { Log.e(TAG, "walk search failed: ${it.message}", it) }
                    .getOrDefault(emptyList())
            }
            val naviResult = naviDeferred.await()
            val walkDetails = searchPaths.await()
            naviResult.fold(
                onSuccess = { paths ->
                    val enriched = attachSearchDetails(paths, walkDetails, originAddress)
                    session.paths = enriched
                    session.selectedPath = enriched.firstOrNull()
                    enriched.firstOrNull()?.let { naviClient.selectRoute(it.id) }
                    _state.update {
                        it.copy(
                            loading = false,
                            paths = enriched,
                            selectedIndex = 0,
                            origin = origin,
                            destinationPoint = dest.location,
                            destinationName = dest.name,
                            showDetails = false,
                            selectedGuideIndex = null,
                            error = if (enriched.isEmpty()) "没有可用步行路线" else null,
                        )
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(loading = false, error = e.message ?: "算路失败") }
                },
            )
        }
    }

    fun select(index: Int) {
        val path = _state.value.paths.getOrNull(index) ?: return
        session.selectedPath = path
        naviClient.selectRoute(path.id)
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
        naviClient.selectRoute(path.id)
        return path
    }

    fun announce() {
        val state = _state.value
        val path = state.paths.getOrNull(state.selectedIndex)
        val dest = state.destinationName.ifBlank { "目的地" }
        val text = when {
            state.loading -> "正在规划到${dest}的步行路线"
            state.error != null -> state.error
            path != null -> "目的地$dest。${path.overviewText()}，${path.cardStats()}"
            else -> "没有可用步行路线"
        }
        tts.speak(text.orEmpty(), flush = true)
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
