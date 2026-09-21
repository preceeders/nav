package com.hu.nav.data.amap

import android.content.Context
import android.util.Log
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.AMapNavi
import com.hu.nav.BuildConfig

object AMapPrivacy {
    private const val TAG = "AMapPrivacy"

    fun agree(context: Context) {
        val app = context.applicationContext
        MapsInitializer.updatePrivacyShow(app, true, true)
        MapsInitializer.updatePrivacyAgree(app, true)
        AMapLocationClient.updatePrivacyShow(app, true, true)
        AMapLocationClient.updatePrivacyAgree(app, true)
        runCatching {
            val clazz = Class.forName("com.amap.api.services.core.ServiceSettings")
            clazz.getMethod("updatePrivacyShow", Context::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(null, app, true, true)
            clazz.getMethod("updatePrivacyAgree", Context::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, app, true)
        }.onFailure { Log.w(TAG, "Search privacy: ${it.message}") }
        runCatching {
            val clazz = Class.forName("com.amap.api.navi.NaviSetting")
            clazz.getMethod("updatePrivacyShow", Context::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(null, app, true, true)
            clazz.getMethod("updatePrivacyAgree", Context::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, app, true)
        }.onFailure { Log.w(TAG, "NaviSetting privacy: ${it.message}") }
        if (BuildConfig.AMAP_KEY.isNotBlank()) {
            runCatching { MapsInitializer.setApiKey(BuildConfig.AMAP_KEY) }
            runCatching { AMapNavi.setApiKey(app, BuildConfig.AMAP_KEY) }
        }
        Log.i(TAG, "package=${app.packageName} sha1=${AppSigning.sha1(app)}")
    }
}
