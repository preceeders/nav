package com.hu.nav.data.amap

import android.content.Context
import android.util.Log
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.hu.nav.domain.model.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DeviceLocation(
    val point: GeoPoint,
    val accuracy: Float,
    val bearing: Float,
    val city: String,
    val address: String,
)

class AMapLocationClientWrapper(context: Context) {
    private val app = context.applicationContext

    fun locations(): Flow<DeviceLocation> = callbackFlow {
        val client = AMapLocationClient(app)
        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            interval = 2000
            isNeedAddress = true
            isOnceLocation = false
            isSensorEnable = true
        }
        client.setLocationOption(option)
        client.setLocationListener { loc ->
            if (loc == null || loc.errorCode != 0) {
                Log.w(TAG, "location error=${loc?.errorCode} ${loc?.errorInfo}")
                return@setLocationListener
            }
            val point = GeoPoint(loc.latitude, loc.longitude)
            if (!point.isValid()) return@setLocationListener
            trySend(
                DeviceLocation(
                    point = point,
                    accuracy = loc.accuracy,
                    bearing = loc.bearing,
                    city = loc.city.orEmpty(),
                    address = loc.address.orEmpty(),
                ),
            )
        }
        client.startLocation()
        awaitClose {
            client.stopLocation()
            client.onDestroy()
        }
    }

    private companion object {
        const val TAG = "AMapLocation"
    }
}
