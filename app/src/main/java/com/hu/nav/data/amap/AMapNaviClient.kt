package com.hu.nav.data.amap

import android.content.Context
import android.util.Log
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapModelCross
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviCross
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo
import com.amap.api.navi.model.AMapServiceAreaInfo
import com.amap.api.navi.model.AimLessModeCongestionInfo
import com.amap.api.navi.model.AimLessModeStat
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.NaviTick
import com.hu.nav.domain.model.WalkPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

sealed class NaviSdkEvent {
    data object InitSuccess : NaviSdkEvent()
    data class InitFailure(val message: String) : NaviSdkEvent()
    data class RouteSuccess(val paths: List<WalkPath>) : NaviSdkEvent()
    data class RouteFailure(val code: Int, val message: String) : NaviSdkEvent()
    data class Tick(val tick: NaviTick) : NaviSdkEvent()
    data class MatchedLocation(val point: GeoPoint, val bearing: Float, val accuracy: Float) : NaviSdkEvent()
    data object SdkArrived : NaviSdkEvent()
    data object SdkYaw : NaviSdkEvent()
    data class GpsWeak(val weak: Boolean) : NaviSdkEvent()
}

class AMapNaviClient(context: Context) {
    private val app = context.applicationContext
    private val _events = MutableSharedFlow<NaviSdkEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NaviSdkEvent> = _events.asSharedFlow()

    @Volatile private var navi: AMapNavi? = null
    private val initialized = AtomicBoolean(false)
    private var initDeferred: CompletableDeferred<Unit> = CompletableDeferred()
    private var routeDeferred: CompletableDeferred<Result<List<WalkPath>>>? = null
    private var lastLocation: GeoPoint = GeoPoint(0.0, 0.0)
    private var lastBearing: Float = 0f
    private var lastAccuracy: Float = 0f
    private var destination: GeoPoint? = null

    fun getNavi(): AMapNavi = requireNotNull(navi) { "AMapNavi 未初始化" }

    suspend fun prepare() {
        if (navi == null) {
            val created = AMapNavi.getInstance(app)
            created.addAMapNaviListener(listener)
            runCatching { created.setUseInnerVoice(false) }
            navi = created
        }
        if (!initialized.get()) {
            withTimeout(15_000) { initDeferred.await() }
        }
    }

    suspend fun calculateWalkRoutes(from: GeoPoint, to: GeoPoint): Result<List<WalkPath>> {
        prepare()
        destination = to
        val deferred = CompletableDeferred<Result<List<WalkPath>>>()
        routeDeferred = deferred
        val ok = getNavi().calculateWalkRoute(
            NaviLatLng(from.lat, from.lng),
            NaviLatLng(to.lat, to.lng),
        )
        if (!ok) {
            val failure = Result.failure<List<WalkPath>>(IllegalStateException("步行算路请求未发出"))
            routeDeferred = null
            return failure
        }
        return withTimeout(20_000) { deferred.await() }
    }

    fun startGpsNavi(pathId: Int): Boolean {
        val instance = navi ?: return false
        instance.selectRouteId(pathId)
        return instance.startNavi(NaviType.GPS)
    }

    fun selectRoute(pathId: Int) {
        navi?.selectRouteId(pathId)
    }

    fun stopNavi() {
        navi?.stopNavi()
    }

    fun destroy() {
        navi?.removeAMapNaviListener(listener)
        navi?.stopNavi()
        AMapNavi.destroy()
        navi = null
        initialized.set(false)
        initDeferred = CompletableDeferred()
    }

    private fun emitPathsFromSdk(): List<WalkPath> {
        val map = navi?.naviPaths.orEmpty()
        if (map.isEmpty()) {
            val single = navi?.naviPath
            return if (single != null) listOf(PathMapper.toWalkPath(0, single)) else emptyList()
        }
        return map.map { (id, path) -> PathMapper.toWalkPath(id, path) }
    }

    private val listener = object : AMapNaviListener {
        override fun onInitNaviFailure() {
            Log.e(TAG, "onInitNaviFailure")
            _events.tryEmit(NaviSdkEvent.InitFailure("导航引擎初始化失败"))
            if (!initDeferred.isCompleted) {
                initDeferred.completeExceptionally(IllegalStateException("导航引擎初始化失败"))
            }
        }

        override fun onInitNaviSuccess() {
            Log.i(TAG, "onInitNaviSuccess")
            initialized.set(true)
            if (!initDeferred.isCompleted) initDeferred.complete(Unit)
            _events.tryEmit(NaviSdkEvent.InitSuccess)
        }

        override fun onStartNavi(type: Int) = Unit
        override fun onTrafficStatusUpdate() = Unit

        override fun onLocationChange(location: AMapNaviLocation?) {
            val coord = location?.coord ?: return
            lastLocation = GeoPoint(coord.latitude, coord.longitude)
            lastBearing = location.bearing
            lastAccuracy = location.accuracy
            _events.tryEmit(NaviSdkEvent.MatchedLocation(lastLocation, lastBearing, lastAccuracy))
        }

        override fun onGetNavigationText(type: Int, text: String?) {
            Log.i(TAG, "SDK sound suppressed (not in whitelist) - \"${text.orEmpty()}\"")
        }

        @Deprecated("Deprecated in SDK")
        override fun onGetNavigationText(text: String?) = Unit

        override fun onEndEmulatorNavi() = Unit

        override fun onArriveDestination() {
            _events.tryEmit(NaviSdkEvent.SdkArrived)
        }

        @Deprecated("Deprecated in SDK")
        override fun onCalculateRouteFailure(errorInfo: Int) = Unit

        override fun onReCalculateRouteForYaw() {
            _events.tryEmit(NaviSdkEvent.SdkYaw)
        }

        override fun onReCalculateRouteForTrafficJam() = Unit
        override fun onArrivedWayPoint(wayID: Int) = Unit
        override fun onGpsOpenStatus(enabled: Boolean) = Unit

        override fun onNaviInfoUpdate(naviInfo: NaviInfo?) {
            val info = naviInfo ?: return
            val dest = destination
            val straight = if (dest != null && lastLocation.isValid()) {
                com.hu.nav.domain.model.GeoMath.distanceMeters(lastLocation, dest)
            } else {
                info.pathRetainDistance.toDouble()
            }
            val tick = NaviTick(
                location = lastLocation,
                bearing = lastBearing,
                accuracy = lastAccuracy,
                curStep = info.curStep,
                curLink = info.curLink,
                curStepRemainMeters = info.curStepRetainDistance,
                routeRemainMeters = info.pathRetainDistance,
                routeRemainSeconds = info.pathRetainTime,
                currentRoadName = info.currentRoadName.orEmpty(),
                nextRoadName = info.nextRoadName.orEmpty(),
                iconType = info.iconType,
                straightToDestMeters = straight,
            )
            _events.tryEmit(NaviSdkEvent.Tick(tick))
        }

        override fun updateCameraInfo(infoArray: Array<AMapNaviCameraInfo>?) = Unit
        override fun onServiceAreaUpdate(infoArray: Array<AMapServiceAreaInfo>?) = Unit
        override fun showCross(aMapNaviCross: AMapNaviCross?) = Unit
        override fun hideCross() = Unit
        @Deprecated("Deprecated in SDK")
        override fun showLaneInfo(laneInfos: Array<AMapLaneInfo>?, bytes: ByteArray?, bytes1: ByteArray?) = Unit
        override fun showLaneInfo(laneInfo: AMapLaneInfo?) = Unit
        override fun hideLaneInfo() = Unit

        @Deprecated("Deprecated in SDK")
        override fun onCalculateRouteSuccess(routeIds: IntArray?) = Unit

        override fun notifyParallelRoad(parallelRoadType: Int) = Unit
        @Deprecated("Deprecated in SDK")
        override fun OnUpdateTrafficFacility(infos: Array<AMapNaviTrafficFacilityInfo>?) = Unit
        @Deprecated("Deprecated in SDK")
        override fun OnUpdateTrafficFacility(info: AMapNaviTrafficFacilityInfo?) = Unit
        @Deprecated("Deprecated in SDK")
        override fun updateAimlessModeStatistics(stat: AimLessModeStat?) = Unit
        @Deprecated("Deprecated in SDK")
        override fun updateAimlessModeCongestionInfo(info: AimLessModeCongestionInfo?) = Unit
        override fun onPlayRing(type: Int) = Unit

        override fun onCalculateRouteSuccess(routeResult: AMapCalcRouteResult?) {
            val paths = emitPathsFromSdk()
            Log.i(TAG, "onCalculateRouteSuccess paths=${paths.size}")
            routeDeferred?.complete(Result.success(paths))
            routeDeferred = null
            _events.tryEmit(NaviSdkEvent.RouteSuccess(paths))
        }

        override fun onCalculateRouteFailure(routeResult: AMapCalcRouteResult?) {
            val code = routeResult?.errorCode ?: -1
            val msg = routeResult?.errorDescription ?: "算路失败"
            Log.e(TAG, "onCalculateRouteFailure code=$code $msg")
            val failure = Result.failure<List<WalkPath>>(IllegalStateException("$msg ($code)"))
            routeDeferred?.complete(failure)
            routeDeferred = null
            _events.tryEmit(NaviSdkEvent.RouteFailure(code, msg))
        }

        override fun onNaviRouteNotify(notifyData: com.amap.api.navi.model.AMapNaviRouteNotifyData?) = Unit
        override fun onGpsSignalWeak(isWeak: Boolean) {
            _events.tryEmit(NaviSdkEvent.GpsWeak(isWeak))
        }

        override fun hideModeCross() = Unit
        override fun showModeCross(cross: AMapModelCross?) = Unit
        override fun updateIntervalCameraInfo(
            start: AMapNaviCameraInfo?,
            end: AMapNaviCameraInfo?,
            status: Int,
        ) = Unit
    }

    private companion object {
        const val TAG = "AMapNaviClient"
    }
}
