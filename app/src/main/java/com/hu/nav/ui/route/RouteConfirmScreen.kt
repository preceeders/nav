package com.hu.nav.ui.route

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hu.nav.domain.model.TravelMode
import com.hu.nav.domain.model.WalkPath
import com.hu.nav.ui.common.BottomSheetCard
import com.hu.nav.ui.common.MapOverlayScaffold
import com.hu.nav.ui.common.SheetActionBar
import com.hu.nav.ui.map.RoutePreviewMap
import com.hu.nav.ui.theme.NavAccent
import com.hu.nav.ui.theme.NavMuted
import com.hu.nav.ui.theme.NavStroke

@Composable
fun RouteConfirmScreen(
    viewModel: RouteViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected = state.paths.getOrNull(state.selectedIndex)
    var notice by remember { mutableStateOf<String?>(null) }
    var recenterToken by remember { mutableIntStateOf(0) }
    BackHandler {
        if (state.showDetails) viewModel.closeDetails() else onBack()
    }

    MapOverlayScaffold(
        onSettings = onOpenSettings,
        onRecenter = { recenterToken++ },
        onAnnounce = viewModel::announce,
        onFavoriteUnavailable = { notice = "收藏功能暂未开放" },
        showSideActions = true,
        showFavorites = false,
        notice = notice,
        onConsumeNotice = { notice = null },
        map = {
            val focused = selected
                ?.takeIf { state.showDetails }
                ?.let { path ->
                    state.selectedGuideIndex?.let { path.guideFocusPolyline(it) }
                }
                .orEmpty()
            val focusedLabel = selected
                ?.takeIf { state.showDetails }
                ?.displayGuides
                ?.getOrNull(state.selectedGuideIndex ?: -1)
                ?.instruction
                .orEmpty()
            RoutePreviewMap(
                paths = state.paths,
                selectedIndex = state.selectedIndex,
                origin = state.origin,
                destination = state.destinationPoint,
                destinationName = state.destinationName,
                showMyLocation = true,
                recenterToken = recenterToken,
                focusedPolyline = focused,
                focusedLabel = focusedLabel,
                modifier = Modifier.fillMaxSize(),
            )
        },
        bottomContent = {
            BottomSheetCard(modifier = Modifier.fillMaxHeight(0.62f)) {
                Column(Modifier.fillMaxSize()) {
                    SheetTitle(
                        title = if (state.showDetails) "路线详情" else "路线选择",
                        onBack = {
                            if (state.showDetails) viewModel.closeDetails() else onBack()
                        },
                        backDescription = if (state.showDetails) "返回路线选择" else "返回搜索",
                    )
                    if (!state.showDetails) {
                        TravelModeRow(
                            travelMode = state.travelMode,
                            onTravelMode = viewModel::setTravelMode,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    when {
                        state.loading -> {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = NavAccent)
                                    Spacer(Modifier.height(12.dp))
                                    val loadingText = if (state.travelMode == TravelMode.Transit) {
                                        "正在规划公交路线"
                                    } else {
                                        "正在规划步行路线"
                                    }
                                    Text(loadingText, color = Color(0xFF666666))
                                }
                            }
                            SheetActionBar(onDepart = onStart, departEnabled = false)
                        }
                        state.error != null -> {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(state.error ?: "", color = Color(0xFFB00020))
                                Button(
                                    onClick = viewModel::calculate,
                                    colors = ButtonDefaults.buttonColors(containerColor = NavAccent),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("重试") }
                            }
                            SheetActionBar(onDepart = onStart, departEnabled = false)
                        }
                        state.showDetails && selected != null -> {
                            RouteDetailsBody(
                                path = selected,
                                index = state.selectedIndex,
                                selectedGuideIndex = state.selectedGuideIndex,
                                onSelectGuide = viewModel::selectGuide,
                                modifier = Modifier.weight(1f),
                            )
                            SheetActionBar(onDepart = onStart)
                        }
                        else -> {
                            RoutePickBody(
                                paths = state.paths,
                                selectedIndex = state.selectedIndex,
                                onSelect = viewModel::select,
                                onOpenDetails = viewModel::openDetails,
                                modifier = Modifier.weight(1f),
                            )
                            SheetActionBar(
                                onDepart = onStart,
                                departEnabled = state.paths.isNotEmpty(),
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SheetTitle(
    title: String,
    onBack: () -> Unit,
    backDescription: String = "返回",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = backDescription },
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = Color(0xFF444444))
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color(0xFF222222))
    }
}

@Composable
private fun TravelModeRow(
    travelMode: TravelMode,
    onTravelMode: (TravelMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeChip(
            text = "步行路线",
            selected = travelMode == TravelMode.Walk,
            onClick = { onTravelMode(TravelMode.Walk) },
        )
        ModeChip(
            text = "公交路线",
            selected = travelMode == TravelMode.Transit,
            onClick = { onTravelMode(TravelMode.Transit) },
        )
    }
}

@Composable
private fun RoutePickBody(
    paths: List<WalkPath>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onOpenDetails: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(paths, key = { index, path -> "$index-${path.id}" }) { index, path ->
                RouteCard(
                    path = path,
                    index = index,
                    selected = selectedIndex == index,
                    onSelect = { onSelect(index) },
                    onDetails = { onOpenDetails(index) },
                )
            }
        }
    }
}

@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val desc = if (selected) "$text，已选中" else "$text，点两下切换"
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) NavMuted else Color.Transparent)
            .then(
                if (selected) Modifier.border(1.dp, NavStroke, RoundedCornerShape(20.dp))
                else Modifier.border(1.dp, NavStroke.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = desc },
    ) {
        Text(
            text,
            color = if (selected) Color(0xFF222222) else Color(0xFF888888),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun RouteCard(
    path: WalkPath,
    index: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onDetails: () -> Unit,
) {
    val title = path.routeTitle(index)
    val summary = "${path.durationLabel()}，$title，${path.cardStats()}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) NavAccent else NavStroke, RoundedCornerShape(16.dp))
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics {
                contentDescription = summary + if (selected) "，已选中" else "，点两下选中"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(path.durationLabel(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222))
            Spacer(Modifier.padding(start = 10.dp))
            Text(title, fontSize = 14.sp, color = Color(0xFF888888), modifier = Modifier.weight(1f))
            Text(
                "路线详情",
                color = NavAccent,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onDetails)
                    .padding(4.dp)
                    .semantics { contentDescription = "查看${title}路线详情" },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(path.cardStats(), fontSize = 13.sp, color = Color(0xFF888888))
    }
}

@Composable
private fun RouteDetailsBody(
    path: WalkPath,
    index: Int,
    selectedGuideIndex: Int?,
    onSelectGuide: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val guides = path.displayGuides
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        RouteSummaryBanner(path = path, index = index)
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(guides, key = { i, guide -> "$i-${guide.instruction}" }) { i, guide ->
                val selected = selectedGuideIndex == i
                val desc = buildString {
                    append("第${i + 1}段，${guide.instruction}")
                    if (selected) append("，已在地图上显示这一段，点两下恢复整条路线")
                    else append("，点两下在地图上查看这一段")
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) NavMuted else Color.Transparent)
                        .clickable { onSelectGuide(i) }
                        .padding(vertical = 12.dp, horizontal = 4.dp)
                        .semantics { contentDescription = desc },
                ) {
                    Text(guide.instruction, fontSize = 15.sp, color = Color(0xFF222222), lineHeight = 22.sp)
                }
                HorizontalDivider(color = NavStroke.copy(alpha = 0.6f), thickness = 0.4.dp)
            }
        }
    }
}

@Composable
private fun RouteSummaryBanner(path: WalkPath, index: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NavMuted)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { contentDescription = "${path.durationLabel()}，${path.routeTitle(index)}，${path.cardStats()}" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(path.durationLabel(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222))
            Spacer(Modifier.padding(start = 10.dp))
            Text(path.routeTitle(index), fontSize = 14.sp, color = Color(0xFF888888))
        }
        Spacer(Modifier.height(6.dp))
        Text(path.cardStats(), fontSize = 13.sp, color = Color(0xFF888888))
    }
}

private val ChineseIndex = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

private fun WalkPath.routeTitle(index: Int): String {
    if (isTransit && lineSummary.isNotBlank()) return lineSummary
    if (index == 0) return "推荐路线"
    val label = ChineseIndex.getOrNull(index) ?: (index + 1).toString()
    return "路线$label"
}
