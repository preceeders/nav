package com.hu.nav.domain.model

object TransitCopy {
    fun shortLineName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val cut = trimmed.indexOf('(').takeIf { it > 0 } ?: trimmed.indexOf('（').takeIf { it > 0 }
        return if (cut != null) trimmed.substring(0, cut).trim() else trimmed
    }

    fun kindOf(lineName: String, lineType: String = ""): TransitKind {
        val hay = "$lineName $lineType"
        return when {
            hay.contains("地铁") || hay.contains("轻轨") || hay.contains("磁悬浮") ||
                hay.contains("有轨") || hay.contains("城际") && hay.contains("轨") -> TransitKind.Subway
            hay.contains("火车") || hay.contains("高铁") || hay.contains("动车") ||
                hay.contains("铁路") -> TransitKind.Railway
            else -> TransitKind.Bus
        }
    }

    fun rideInstruction(
        kind: TransitKind,
        lineName: String,
        departure: String,
        arrival: String,
        passStationCount: Int,
        entrance: String = "",
        exit: String = "",
    ): String {
        val verb = when (kind) {
            TransitKind.Subway -> "乘坐"
            TransitKind.Railway -> "乘坐"
            TransitKind.Taxi -> "打车"
            else -> "乘坐"
        }
        val line = lineName.ifBlank {
            when (kind) {
                TransitKind.Subway -> "地铁"
                TransitKind.Railway -> "火车"
                TransitKind.Taxi -> "出租车"
                else -> "公交"
            }
        }
        return buildString {
            if (entrance.isNotBlank()) append("从${entrance}进站，")
            if (kind == TransitKind.Taxi) {
                append("打车")
                if (departure.isNotBlank()) append("从$departure")
                if (arrival.isNotBlank()) append("到$arrival")
            } else {
                append("$verb$line")
                if (departure.isNotBlank()) append("，从${departure}上车")
                if (passStationCount > 0) append("，经过${passStationCount}站")
                if (arrival.isNotBlank()) append("，在${arrival}下车")
            }
            if (exit.isNotBlank()) append("，从${exit}出站")
        }
    }

    fun walkTo(meters: Int, place: String = ""): String {
        val dist = if (meters >= 1000) {
            String.format("%.1f公里", meters / 1000.0)
        } else {
            "${meters.coerceAtLeast(1)}米"
        }
        return if (place.isNotBlank()) "步行约$dist，前往$place" else "步行约$dist"
    }

    fun boarding(lineName: String, stop: String): String {
        val line = lineName.ifBlank { "公交" }
        return if (stop.isNotBlank()) "请在${stop}乘坐$line" else "请乘坐$line"
    }

    fun nextStop(name: String): String = "下一站，$name"

    fun alight(stop: String): String {
        return if (stop.isNotBlank()) "即将到站，请在${stop}下车" else "即将到站，请下车"
    }
}
