package com.hu.nav.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hu.nav.data.prefs.SettingsStore
import com.hu.nav.domain.model.NavigationConfig
import com.hu.nav.domain.nav.NavigationEngine
import com.hu.nav.domain.tts.TtsSpeaker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: SettingsStore,
    engine: NavigationEngine,
    tts: TtsSpeaker,
    onBack: () -> Unit,
) {
    val initial = remember { store.load() }
    var calibration by remember { mutableStateOf(initial.compassCalibrationEnabled) }
    var speech by remember { mutableFloatStateOf(initial.speechRate) }
    var arrival by remember { mutableFloatStateOf(initial.arrivedStraightMeters.toFloat()) }

    fun persist() {
        val config = NavigationConfig(
            compassCalibrationEnabled = calibration,
            speechRate = speech,
            arrivedStraightMeters = arrival.toDouble(),
            arrivedRemainMeters = arrival.toInt().coerceAtLeast(8),
        )
        store.save(config)
        engine.updateConfig(config)
        tts.speechRate = speech
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导航设置") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "返回" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("语速 ${String.format("%.1f", speech)}")
            Slider(
                value = speech,
                onValueChange = { speech = it },
                onValueChangeFinished = { persist() },
                valueRange = 0.5f..1.8f,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "语速" },
            )
            Text("到达确认半径 ${arrival.toInt()} 米")
            Slider(
                value = arrival,
                onValueChange = { arrival = it },
                onValueChangeFinished = { persist() },
                valueRange = 10f..40f,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "到达确认半径" },
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("开导时校准罗盘朝向")
                Switch(
                    checked = calibration,
                    onCheckedChange = {
                        calibration = it
                        persist()
                    },
                    modifier = Modifier.semantics { contentDescription = if (calibration) "罗盘校准已打开" else "罗盘校准已关闭" },
                )
            }
        }
    }
}
