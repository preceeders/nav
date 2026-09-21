package com.hu.nav.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hu.nav.data.amap.AMapLocationClientWrapper
import com.hu.nav.data.amap.AMapSearchClient
import com.hu.nav.data.prefs.SearchHistoryItem
import com.hu.nav.data.prefs.SearchHistoryStore
import com.hu.nav.domain.model.GeoMath
import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.Poi
import com.hu.nav.domain.nav.NavigationSession
import com.hu.nav.domain.tts.TtsSpeaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchPanel {
    Hidden,
    History,
    Results,
}

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val results: List<Poi> = emptyList(),
    val history: List<SearchHistoryItem> = emptyList(),
    val panel: SearchPanel = SearchPanel.Hidden,
    val hasLocation: Boolean = false,
    val location: GeoPoint? = null,
    val notice: String? = null,
    val recenterToken: Int = 0,
)

class SearchViewModel(
    private val search: AMapSearchClient,
    private val locationClient: AMapLocationClientWrapper,
    private val session: NavigationSession,
    private val historyStore: SearchHistoryStore,
    private val tts: TtsSpeaker,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(history = historyStore.load()))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            locationClient.locations().collect { loc ->
                session.origin = loc.point
                session.originAddress = loc.address
                session.lastCity = loc.city
                val previous = _state.value.location
                val moved = previous == null || GeoMath.distanceMeters(previous, loc.point) >= 8
                if (!moved) return@collect
                _state.update {
                    it.copy(
                        hasLocation = true,
                        location = loc.point,
                    )
                }
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value, error = null, notice = null) }
    }

    fun openPanel() {
        _state.update {
            it.copy(
                panel = if (it.results.isNotEmpty()) SearchPanel.Results else SearchPanel.History,
                notice = null,
            )
        }
    }

    fun closePanel() {
        searchJob?.cancel()
        _state.update {
            it.copy(
                panel = SearchPanel.Hidden,
                loading = false,
                error = null,
                notice = null,
            )
        }
    }

    fun backFromResults() {
        _state.update {
            it.copy(
                panel = SearchPanel.History,
                results = emptyList(),
                error = null,
                loading = false,
            )
        }
    }

    fun clearQuery() {
        _state.update { it.copy(query = "", results = emptyList(), error = null, panel = SearchPanel.History) }
    }

    fun clearHistory() {
        _state.update { it.copy(history = historyStore.clear()) }
    }

    fun searchCategory(keyword: String) {
        _state.update { it.copy(query = keyword) }
        search()
    }

    fun showNotice(text: String) {
        _state.update { it.copy(notice = text) }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun requestRecenter() {
        _state.update { it.copy(recenterToken = it.recenterToken + 1) }
    }

    fun announce() {
        val address = session.originAddress.trim()
        val text = when {
            address.isNotBlank() -> "当前位置，$address"
            _state.value.hasLocation -> "已定位，请搜索目的地"
            else -> "正在定位，请稍候"
        }
        tts.speak(text, flush = true)
    }

    fun search() {
        val keyword = _state.value.query.trim()
        if (keyword.isEmpty()) {
            _state.update { it.copy(error = "请输入目的地", panel = SearchPanel.History) }
            return
        }
        val origin = session.origin
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, panel = SearchPanel.Results, notice = null) }
            runCatching { search.searchPoi(keyword, session.lastCity, origin) }
                .onSuccess { list ->
                    val history = historyStore.addQuery(keyword)
                    _state.update {
                        it.copy(
                            loading = false,
                            results = list,
                            history = history,
                            panel = SearchPanel.Results,
                            error = if (list.isEmpty()) "没有找到相关地点" else null,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = e.message ?: "搜索失败",
                            panel = SearchPanel.Results,
                        )
                    }
                }
        }
    }

    fun select(poi: Poi) {
        session.destination = poi
        val history = historyStore.addPoi(poi)
        _state.update { it.copy(history = history) }
    }

    fun origin(): GeoPoint? = session.origin
}
