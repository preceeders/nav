package com.hu.nav.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hu.nav.domain.model.GeoMath
import com.hu.nav.domain.nav.JourneyUiState
import com.hu.nav.domain.nav.NavigationEngine
import com.hu.nav.domain.nav.NavigationSession
import com.hu.nav.domain.nav.WalkingPhase
import com.hu.nav.service.NaviForegroundService
import com.hu.nav.ui.common.MapRoundButton
import com.hu.nav.ui.common.MapSettingsButton
import com.hu.nav.ui.common.NoticeBanner
import com.hu.nav.ui.map.RoutePreviewMap
import kotlin.math.abs

@Composable
fun JourneyScreen(
    engine: NavigationEngine,
    session: NavigationSession,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by engine.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    var recenterToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val origin = session.origin ?: return@LaunchedEffect
        val dest = session.destination ?: return@LaunchedEffect
        val path = session.selectedPath ?: return@LaunchedEffect
        if (!engine.state.value.running) {
            engine.start(origin, dest, path)
        }
    }

    DisposableEffect(Unit) {
        NaviForegroundService.start(context)
        onDispose {
            if (!engine.state.value.running) {
                NaviForegroundService.stop(context)
            }
        }
    }

    val path = state.path ?: session.selectedPath
    val overview = state.hint.ifBlank {
        path?.overviewText().orEmpty().ifBlank {
            "正在导航到${state.destinationName}"
        }
    }
    val headingCue = headingActionText(state)
    val remainText = remainLabel(state.remainMeters, state.remainSeconds)
    val arrow = headingArrow(state)
    val compassDesc = state.headingDegrees?.let {
        "指南针，北在上方，当前朝向${GeoMath.compassDirection(it)}"
    } ?: "指南针，北在上方"

    Box(Modifier.fillMaxSize()) {
        RoutePreviewMap(
            paths = listOfNotNull(path),
            selectedIndex = 0,
            origin = state.origin ?: session.origin,
            destination = state.destinationPoint ?: session.destination?.location,
            destinationName = state.destinationName.ifBlank { session.destination?.name.orEmpty() },
            showMyLocation = true,
            followMyLocation = true,
            fitToRoute = false,
            showOriginMarker = false,
            showDestinationWindow = false,
            showSystemCompass = false,
            recenterToken = recenterToken,
            modifier = Modifier.fillMaxSize(),
        )

        CompassRose(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-36).dp)
                .semantics { contentDescription = compassDesc },
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(start = 16.dp, end = 64.dp, top = 8.dp, bottom = 8.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InstructionBanner(
                icon = arrow,
                text = overview,
                description = overview,
            )
            InstructionBanner(
                icon = null,
                text = headingCue,
                description = headingCue,
                compact = true,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 12.dp, top = 4.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MapSettingsButton(onSettings = onOpenSettings)
            MapRoundButton(Icons.Default.MyLocation, "回到当前位置") { recenterToken++ }
            MapRoundButton(Icons.Default.Campaign, "播报") { engine.speakCurrentLocation() }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 12.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MapRoundButton(Icons.Default.StarBorder, "收藏，暂未开放") { notice = "收藏功能暂未开放" }
            MapRoundButton(Icons.Default.Folder, "收藏夹，暂未开放") { notice = "收藏功能暂未开放" }
        }

        if (!notice.isNullOrBlank()) {
            NoticeBanner(
                text = notice.orEmpty(),
                onConsumed = { notice = null },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 120.dp)
                    .padding(horizontal = 24.dp),
            )
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter),
            color = Color.White,
            shadowElevation = 8.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
            ) {
                TextButton(
                    onClick = {
                        engine.stop()
                        onStop()
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .semantics { contentDescription = "退出导航" },
                ) {
                    Text("退出", color = Color(0xFF222222), fontSize = 16.sp)
                }
                Text(
                    remainText,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 72.dp)
                        .semantics { contentDescription = remainText },
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                    color = Color(0xFF222222),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InstructionBanner(
    icon: ImageVector?,
    text: String,
    description: String,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xE6101010))
            .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 12.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text,
            color = Color.White,
            fontSize = if (compact) 16.sp else 15.sp,
            fontWeight = if (compact) FontWeight.Medium else FontWeight.Normal,
            maxLines = if (compact) 1 else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CompassRose(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(168.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompassLabel("北", Color(0xFFFF9100), Modifier.align(Alignment.TopCenter))
        CompassLabel("东", Color.White, Modifier.align(Alignment.CenterEnd))
        CompassLabel("南", Color.White, Modifier.align(Alignment.BottomCenter))
        CompassLabel("西", Color.White, Modifier.align(Alignment.CenterStart))
    }
}

@Composable
private fun CompassLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(4.dp))
            .padding(4.dp),
    )
}

private fun headingActionText(state: JourneyUiState): String {
    when (state.phase) {
        WalkingPhase.Calibrating -> return state.hint.ifBlank { "请朝向路线方向" }
        WalkingPhase.OffRoute, WalkingPhase.Rerouting -> return "正在重新规划路线"
        WalkingPhase.Arriving -> return "接近目的地"
        WalkingPhase.Arrived -> return "已到达目的地"
        WalkingPhase.Idle -> return "导航已停止"
        else -> Unit
    }
    val heading = state.headingDegrees
    val expected = state.expectedHeadingDegrees
    if (heading != null && expected != null) {
        val signed = GeoMath.signedAngleDiff(heading, expected)
        val degrees = abs(signed).toInt()
        if (degrees >= 15) {
            return if (signed > 0) "右转${degrees}度" else "左转${degrees}度"
        }
    }
    return state.hint.ifBlank { "直行" }
}

private fun headingArrow(state: JourneyUiState): ImageVector {
    val heading = state.headingDegrees
    val expected = state.expectedHeadingDegrees
    if (heading != null && expected != null) {
        val signed = GeoMath.signedAngleDiff(heading, expected)
        val degrees = abs(signed)
        return when {
            degrees >= 150 -> Icons.Default.Undo
            signed > 25 -> Icons.AutoMirrored.Filled.ArrowForward
            signed < -25 -> Icons.AutoMirrored.Filled.ArrowBack
            else -> Icons.Default.North
        }
    }
    return Icons.Default.North
}

private fun remainLabel(meters: Int, seconds: Int): String {
    val dist = if (meters >= 1000) {
        String.format("%.1f公里", meters / 1000.0)
    } else {
        "${meters}米"
    }
    val minutes = (seconds / 60).coerceAtLeast(if (meters > 0) 1 else 0)
    return if (minutes <= 0) "剩余 $dist" else "剩余 $dist ${minutes}分钟"
}
