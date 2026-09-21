package com.hu.nav.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hu.nav.data.prefs.SearchHistoryItem
import com.hu.nav.domain.model.Poi
import com.hu.nav.ui.common.BottomSheetCard
import com.hu.nav.ui.common.MapOverlayScaffold
import com.hu.nav.ui.map.BrowseMapView
import com.hu.nav.ui.theme.NavAccent
import com.hu.nav.ui.theme.NavMuted
import com.hu.nav.ui.theme.NavStroke

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    missingKey: Boolean,
    onOpenSettings: () -> Unit,
    onPoiSelected: (Poi) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val panelOpen = state.panel != SearchPanel.Hidden
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = panelOpen) {
        if (state.panel == SearchPanel.Results) viewModel.backFromResults() else viewModel.closePanel()
    }

    MapOverlayScaffold(
        onSettings = onOpenSettings,
        onRecenter = { viewModel.requestRecenter() },
        onAnnounce = viewModel::announce,
        onFavoriteUnavailable = { viewModel.showNotice("收藏功能暂未开放") },
        showSideActions = true,
        showFavorites = !panelOpen,
        notice = state.notice,
        onConsumeNotice = viewModel::consumeNotice,
        map = {
            BrowseMapView(
                location = state.location,
                showMyLocation = true,
                recenterToken = state.recenterToken,
                modifier = Modifier.fillMaxSize(),
            )
        },
        bottomContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
            ) {
                if (missingKey && !panelOpen) {
                    Text(
                        "请在项目根目录 local.properties 中配置 AMAP_KEY 后重新编译。",
                        color = Color(0xFFB00020),
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .semantics { contentDescription = "未配置高德 Key" },
                    )
                }
                if (panelOpen) {
                    SearchSheet(
                        state = state,
                        autoFocus = state.panel == SearchPanel.History && state.query.isEmpty(),
                        onQueryChange = viewModel::onQueryChange,
                        onSearch = viewModel::search,
                        onClearQuery = viewModel::clearQuery,
                        onClose = {
                            focusManager.clearFocus()
                            if (state.panel == SearchPanel.Results) {
                                viewModel.backFromResults()
                            } else {
                                viewModel.closePanel()
                            }
                        },
                        onClearHistory = viewModel::clearHistory,
                        onCategory = viewModel::searchCategory,
                        onSelectPoi = { poi ->
                            focusManager.clearFocus()
                            onPoiSelected(poi)
                        },
                        onSelectHistory = { item ->
                            val poi = item.toPoi()
                            if (poi != null) {
                                onPoiSelected(poi)
                            } else {
                                viewModel.onQueryChange(item.query)
                                viewModel.search()
                            }
                        },
                    )
                } else {
                    HomeSearchPill(onClick = viewModel::openPanel)
                }
            }
        },
    )
}

@Composable
private fun HomeSearchPill(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = Color.White,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = "搜索，点两下输入目的地" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF9AA0B4))
            Spacer(Modifier.width(10.dp))
            Text("搜索", color = Color(0xFF9AA0B4), fontSize = 16.sp)
        }
    }
}

@Composable
private fun SearchSheet(
    state: SearchUiState,
    autoFocus: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearQuery: () -> Unit,
    onClose: () -> Unit,
    onClearHistory: () -> Unit,
    onCategory: (String) -> Unit,
    onSelectPoi: (Poi) -> Unit,
    onSelectHistory: (SearchHistoryItem) -> Unit,
) {
    BottomSheetCard(modifier = Modifier.fillMaxHeight(0.72f)) {
        Column(Modifier.fillMaxSize()) {
            SheetSearchBar(
                query = state.query,
                loading = state.loading,
                autoFocus = autoFocus,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onClearQuery = onClearQuery,
                onClose = onClose,
            )
            when (state.panel) {
                SearchPanel.Results -> ResultsBody(
                    state = state,
                    onSelectPoi = onSelectPoi,
                )
                else -> HistoryBody(
                    state = state,
                    onCategory = onCategory,
                    onClearHistory = onClearHistory,
                    onSelectHistory = onSelectHistory,
                )
            }
        }
    }
}

@Composable
private fun SheetSearchBar(
    query: String,
    loading: Boolean,
    autoFocus: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearQuery: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { focus.requestFocus() }
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(NavMuted)
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.semantics { contentDescription = "返回" },
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = Color(0xFF444444))
        }
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("输入目的地", color = Color(0xFF9AA0B4), fontSize = 16.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Color(0xFF222222), fontSize = 16.sp),
                cursorBrush = SolidColor(NavAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .semantics { contentDescription = "目的地输入框" },
            )
        }
        if (query.isNotEmpty()) {
            IconButton(
                onClick = onClearQuery,
                modifier = Modifier
                    .size(32.dp)
                    .semantics { contentDescription = "清除输入" },
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFF9AA0B4), modifier = Modifier.size(18.dp))
            }
        }
        TextButton(
            onClick = onSearch,
            enabled = !loading,
            modifier = Modifier.semantics { contentDescription = "开始搜索" },
        ) {
            Text("搜索", color = NavAccent, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HistoryBody(
    state: SearchUiState,
    onCategory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSelectHistory: (SearchHistoryItem) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(PlaceCategories) { category ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onCategory(category.keyword) }
                        .semantics { contentDescription = "搜索${category.label}" },
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(category.tint.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(category.icon, contentDescription = null, tint = category.tint)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(category.label, fontSize = 12.sp, color = Color(0xFF555555))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("搜索历史", fontSize = 14.sp, color = Color(0xFF888888), modifier = Modifier.weight(1f))
            Text(
                "清除历史",
                color = NavAccent,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onClearHistory)
                    .semantics { contentDescription = "清除搜索历史" }
                    .padding(4.dp),
            )
        }
        state.error?.let {
            Text(
                it,
                color = Color(0xFFB00020),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (state.history.isEmpty()) {
            Text(
                "暂无历史搜索",
                color = Color(0xFF9AA0B4),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.history, key = { it.id + it.query + it.name }) { item ->
                    HistoryRow(item = item, onSelect = { onSelectHistory(item) })
                    HorizontalDivider(color = NavStroke, thickness = 0.6.dp)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: SearchHistoryItem,
    onSelect: () -> Unit,
) {
    val poi = item.toPoi()
    val title = item.name.ifBlank { item.query }
    val desc = if (poi != null) {
        "$title，${item.address}，点两下选择这个目的地"
    } else {
        "$title，点两下搜索"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { contentDescription = desc },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = Color(0xFF222222), fontWeight = FontWeight.Medium)
            if (item.address.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(item.address, fontSize = 13.sp, color = Color(0xFF9AA0B4))
            }
        }
        if (poi != null) {
            Text(
                "去这里",
                color = Color(0xFF333333),
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, NavStroke, RoundedCornerShape(16.dp))
                    .clickable(onClick = onSelect)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics { contentDescription = "去这里，前往$title" },
            )
        }
    }
}

@Composable
private fun ResultsBody(
    state: SearchUiState,
    onSelectPoi: (Poi) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        when {
            state.loading -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NavAccent)
                }
            }
            else -> {
                if (state.error != null) {
                    Text(
                        state.error,
                        color = Color(0xFFB00020),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                ) {
                    items(state.results, key = { it.id + it.name }) { poi ->
                        ResultRow(poi = poi, onSelect = { onSelectPoi(poi) })
                        HorizontalDivider(color = NavStroke, thickness = 0.6.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    poi: Poi,
    onSelect: () -> Unit,
) {
    val distance = poi.distanceMeters?.let { "${it}米" }.orEmpty()
    val supporting = listOf(distance, poi.address).filter { it.isNotBlank() }.joinToString("  ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { contentDescription = "${poi.name}，$supporting，点两下选择" },
    ) {
        Text(poi.name, fontSize = 17.sp, color = Color(0xFF222222), fontWeight = FontWeight.Medium)
        if (supporting.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(supporting, fontSize = 13.sp, color = Color(0xFF9AA0B4))
        }
    }
}

private data class PlaceCategory(
    val keyword: String,
    val label: String,
    val icon: ImageVector,
    val tint: Color,
)

private val PlaceCategories = listOf(
    PlaceCategory("超市", "超市", Icons.Filled.ShoppingBasket, Color(0xFFFF8A65)),
    PlaceCategory("卫生间", "卫生间", Icons.Filled.Wc, Color(0xFFF48FB1)),
    PlaceCategory("美食", "美食", Icons.Filled.Restaurant, Color(0xFFFFB74D)),
    PlaceCategory("地铁", "地铁", Icons.Filled.Subway, Color(0xFFBA68C8)),
    PlaceCategory("公交站", "公交站", Icons.Filled.DirectionsBus, Color(0xFF64B5F6)),
    PlaceCategory("银行", "银行", Icons.Filled.AccountBalance, Color(0xFFFFCC80)),
    PlaceCategory("酒店", "酒店", Icons.Filled.Hotel, Color(0xFF90CAF9)),
)
