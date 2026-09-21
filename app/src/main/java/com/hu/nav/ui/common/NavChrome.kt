package com.hu.nav.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hu.nav.ui.theme.NavAccent
import com.hu.nav.ui.theme.NavMuted
import kotlinx.coroutines.delay

@Composable
fun MapOverlayScaffold(
    onSettings: () -> Unit,
    onRecenter: () -> Unit,
    onAnnounce: () -> Unit,
    onFavoriteUnavailable: () -> Unit,
    showSideActions: Boolean,
    showFavorites: Boolean = showSideActions,
    notice: String?,
    onConsumeNotice: () -> Unit,
    bottomContent: @Composable () -> Unit,
    map: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        map()
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 12.dp, top = 4.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MapSettingsButton(onSettings = onSettings)
            if (showSideActions) {
                MapRoundButton(Icons.Default.MyLocation, "回到当前位置", onRecenter)
                MapRoundButton(Icons.Default.Campaign, "播报", onAnnounce)
            }
        }
        if (showFavorites) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MapRoundButton(Icons.Default.StarBorder, "收藏，暂未开放", onFavoriteUnavailable)
                MapRoundButton(Icons.Default.Folder, "收藏夹，暂未开放", onFavoriteUnavailable)
            }
        }
        if (!notice.isNullOrBlank()) {
            NoticeBanner(
                text = notice,
                onConsumed = onConsumeNotice,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
                    .padding(horizontal = 24.dp),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            bottomContent()
        }
    }
}

@Composable
fun MapRoundButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xCC1C1C1E))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
    }
}

@Composable
fun NoticeBanner(
    text: String,
    onConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(text) {
        delay(2200)
        onConsumed()
    }
    Surface(
        modifier = modifier,
        color = Color(0xCC222222),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
    ) {
        Text(
            text,
            color = Color.White,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { contentDescription = text },
        )
    }
}

@Composable
fun BottomSheetCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = Color.White,
        shadowElevation = 12.dp,
    ) {
        content()
    }
}

@Composable
fun SheetActionBar(
    onBack: (() -> Unit)? = null,
    onDepart: (() -> Unit)? = null,
    departEnabled: Boolean = true,
    backDescription: String = "返回",
    departDescription: String = "开始导航",
    departLabel: String = "开始导航",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .semantics { contentDescription = backDescription },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NavMuted,
                    contentColor = Color(0xFF222222),
                ),
                shape = RoundedCornerShape(24.dp),
                elevation = ButtonDefaults.buttonElevation(0.dp),
            ) {
                Text("返回", fontSize = 16.sp)
            }
        }
        if (onDepart != null) {
            Button(
                onClick = onDepart,
                enabled = departEnabled,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .semantics { contentDescription = departDescription },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NavAccent,
                    contentColor = Color.White,
                    disabledContainerColor = NavAccent.copy(alpha = 0.4f),
                ),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(departLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
