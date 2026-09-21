package com.hu.nav.di

import android.content.Context
import android.util.Log
import com.hu.nav.data.amap.AMapLocationClientWrapper
import com.hu.nav.data.amap.AMapNaviClient
import com.hu.nav.data.amap.AMapSearchClient
import com.hu.nav.data.prefs.SearchHistoryStore
import com.hu.nav.data.prefs.SettingsStore
import com.hu.nav.data.sensor.CompassRepository
import com.hu.nav.data.tts.AndroidTtsSpeaker
import com.hu.nav.domain.nav.NavigationEngine
import com.hu.nav.domain.nav.NavigationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val app = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val session = NavigationSession()
    val settings = SettingsStore(app)
    val searchHistory = SearchHistoryStore(app)
    val tts = AndroidTtsSpeaker(app)
    val search = AMapSearchClient(app)
    val location = AMapLocationClientWrapper(app)
    val navi = AMapNaviClient(app)
    val compass = CompassRepository(app)

    val engine = NavigationEngine(
        naviClient = navi,
        locationClient = location,
        compass = compass,
        tts = tts,
        scope = appScope,
        logger = { Log.i("WalkingPlanner", it) },
    ).also { it.updateConfig(settings.load()) }
}
