package com.hu.nav.data.prefs

import android.content.Context
import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.Poi
import org.json.JSONArray
import org.json.JSONObject

data class SearchHistoryItem(
    val query: String,
    val name: String = query,
    val address: String = "",
    val id: String = "",
    val location: GeoPoint? = null,
) {
    fun toPoi(): Poi? {
        val point = location?.takeIf { it.isValid() } ?: return null
        return Poi(
            id = id.ifBlank { "${point.lat},${point.lng}" },
            name = name.ifBlank { query },
            address = address,
            location = point,
        )
    }
}

class SearchHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("nav_search_history", Context.MODE_PRIVATE)

    fun load(): List<SearchHistoryItem> {
        val raw = prefs.getString(KEY, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(obj.toItem())
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addQuery(query: String): List<SearchHistoryItem> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return load()
        return saveFront(
            SearchHistoryItem(query = keyword, name = keyword),
            sameAs = { it.query.equals(keyword, ignoreCase = true) && it.location == null },
        )
    }

    fun addPoi(poi: Poi): List<SearchHistoryItem> {
        return saveFront(
            SearchHistoryItem(
                query = poi.name,
                name = poi.name,
                address = poi.address,
                id = poi.id,
                location = poi.location,
            ),
            sameAs = { existing ->
                (poi.id.isNotBlank() && existing.id == poi.id) ||
                    existing.name.equals(poi.name, ignoreCase = true)
            },
        )
    }

    private fun saveFront(
        item: SearchHistoryItem,
        sameAs: (SearchHistoryItem) -> Boolean,
    ): List<SearchHistoryItem> {
        val next = (listOf(item) + load().filterNot(sameAs)).take(MAX_ITEMS)
        val array = JSONArray()
        next.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
        return next
    }

    fun clear(): List<SearchHistoryItem> {
        prefs.edit().remove(KEY).apply()
        return emptyList()
    }

    private fun SearchHistoryItem.toJson(): JSONObject = JSONObject().apply {
        put("query", query)
        put("name", name)
        put("address", address)
        put("id", id)
        put("lat", location?.lat ?: 0.0)
        put("lng", location?.lng ?: 0.0)
    }

    private fun JSONObject.toItem(): SearchHistoryItem {
        val lat = optDouble("lat", 0.0)
        val lng = optDouble("lng", 0.0)
        val point = GeoPoint(lat, lng).takeIf { it.isValid() }
        return SearchHistoryItem(
            query = optString("query"),
            name = optString("name").ifBlank { optString("query") },
            address = optString("address"),
            id = optString("id"),
            location = point,
        )
    }

    private companion object {
        const val KEY = "items"
        const val MAX_ITEMS = 20
    }
}
