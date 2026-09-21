package com.hu.nav.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun MapSettingsButton(
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onSettings,
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0x99000000))
            .semantics { contentDescription = "打开导航设置" },
    ) {
        Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White)
    }
}
