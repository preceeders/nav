package com.hu.nav.data.amap

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

object AppSigning {
    private const val TAG = "AppSigning"

    fun sha1(context: Context): String {
        return runCatching {
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES,
                ).signatures
            }
            val bytes = signatures?.firstOrNull()?.toByteArray() ?: return ""
            val digest = MessageDigest.getInstance("SHA1").digest(bytes)
            digest.joinToString(":") { "%02X".format(it) }
        }.onFailure { Log.w(TAG, "sha1 failed: ${it.message}") }.getOrDefault("")
    }
}
