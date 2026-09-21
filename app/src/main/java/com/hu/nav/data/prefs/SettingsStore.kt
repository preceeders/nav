package com.hu.nav.data.prefs

import android.content.Context
import com.hu.nav.domain.model.NavigationConfig

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("nav_settings", Context.MODE_PRIVATE)

    fun load(): NavigationConfig {
        return NavigationConfig(
            compassCalibrationEnabled = prefs.getBoolean(KEY_CALIBRATION, true),
            arrivedStraightMeters = prefs.getFloat(KEY_ARRIVAL, 18f).toDouble(),
            arrivedRemainMeters = prefs.getInt(KEY_ARRIVAL_REMAIN, 12),
            speechRate = prefs.getFloat(KEY_SPEECH, 1.0f),
        )
    }

    fun save(config: NavigationConfig) {
        prefs.edit()
            .putBoolean(KEY_CALIBRATION, config.compassCalibrationEnabled)
            .putFloat(KEY_ARRIVAL, config.arrivedStraightMeters.toFloat())
            .putInt(KEY_ARRIVAL_REMAIN, config.arrivedRemainMeters)
            .putFloat(KEY_SPEECH, config.speechRate)
            .apply()
    }

    private companion object {
        const val KEY_CALIBRATION = "compass_calibration"
        const val KEY_ARRIVAL = "arrival_straight"
        const val KEY_ARRIVAL_REMAIN = "arrival_remain"
        const val KEY_SPEECH = "speech_rate"
    }
}
