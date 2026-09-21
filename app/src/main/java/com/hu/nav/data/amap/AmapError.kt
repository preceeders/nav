package com.hu.nav.data.amap

object AmapError {
    fun searchMessage(code: Int, sha1: String, packageName: String): String {
        val bindHint = "请在高德控制台把 Key 绑成 Android 平台，包名 $packageName，SHA1 $sha1，并开通搜索服务。"
        return when (code) {
            1001 -> "开发者签名未通过。$bindHint"
            1002 -> "高德 Key 不正确或过期。请检查 local.properties 的 AMAP_KEY。"
            1003 -> "当前 Key 没有搜索权限，请在控制台开通搜索服务。"
            1008 -> "POI 搜索失败（1008，安全码未通过）。$bindHint"
            1009 -> "Key 与平台不符。请申请 Android 平台 Key，不要用 Web/JS Key。"
            1012 -> "Key 未开通对应服务。请在控制台开通搜索。"
            2000, 2001 -> "搜索参数无效，请换个关键词再试。"
            else -> "POI 搜索失败，错误码 $code。$bindHint"
        }
    }

    fun routeMessage(code: Int, sha1: String, packageName: String): String {
        val bindHint = "请在高德控制台把 Key 绑成 Android 平台，包名 $packageName，SHA1 $sha1，并开通搜索服务。"
        return when (code) {
            1001 -> "开发者签名未通过。$bindHint"
            1002 -> "高德 Key 不正确或过期。请检查 local.properties 的 AMAP_KEY。"
            1003, 1012 -> "当前 Key 没有路径规划权限，请在控制台开通搜索服务。"
            1008 -> "公交算路失败（1008，安全码未通过）。$bindHint"
            1009 -> "Key 与平台不符。请申请 Android 平台 Key，不要用 Web/JS Key。"
            2000, 2001 -> "公交算路参数无效，请确认已定位并稍后重试。"
            3000, 3001, 3002, 3003 -> "没有可用公交路线，可改用步行或换个目的地。"
            else -> "公交算路失败，错误码 $code。$bindHint"
        }
    }
}
