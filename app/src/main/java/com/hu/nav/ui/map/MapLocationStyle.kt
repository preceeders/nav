package com.hu.nav.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.amap.api.maps.AMap
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.MyLocationStyle

internal fun enableMyLocationTriangle(
    map: AMap,
    followCenter: Boolean = true,
    iconColor: String = "#1B5E20",
) {
    val style = MyLocationStyle().apply {
        myLocationType(
            if (followCenter) {
                MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE
            } else {
                MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER
            },
        )
        interval(1000)
        myLocationIcon(BitmapDescriptorFactory.fromBitmap(createHeadingTriangle(iconColor)))
        anchor(0.5f, 0.5f)
        strokeWidth(1f)
        strokeColor(0x664FC3F7)
        radiusFillColor(0x224FC3F7)
        showMyLocation(true)
    }
    map.myLocationStyle = style
    map.uiSettings.isMyLocationButtonEnabled = false
    map.isMyLocationEnabled = true
}

internal fun createHeadingTriangle(colorHex: String = "#1B5E20"): Bitmap {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(colorHex)
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
    }
    val path = Path().apply {
        moveTo(size / 2f, 8f)
        lineTo(size - 14f, size - 10f)
        lineTo(size / 2f, size - 28f)
        lineTo(14f, size - 10f)
        close()
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, stroke)
    return bitmap
}
