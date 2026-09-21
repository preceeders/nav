package com.hu.nav.data.amap

import android.content.Context
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.amap.api.services.route.BusPath
import com.amap.api.services.route.BusRouteResult
import com.amap.api.services.route.DriveRouteResult
import com.amap.api.services.route.RideRouteResult
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.WalkPath
import com.amap.api.services.route.WalkRouteResult
import com.hu.nav.domain.model.GeoMath
import com.hu.nav.domain.model.GeoPoint
import com.hu.nav.domain.model.Poi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ReverseGeo(
    val address: String,
    val city: String,
)

class AMapSearchClient(context: Context) {
    private val app = context.applicationContext
    private val sha1 = AppSigning.sha1(app)

    suspend fun searchPoi(keyword: String, city: String, origin: GeoPoint?): List<Poi> {
        if (keyword.isBlank()) return emptyList()
        return suspendCancellableCoroutine { cont ->
            val query = PoiSearch.Query(keyword, "", city).apply {
                pageSize = 20
                pageNum = 1
                cityLimit = city.isNotBlank()
            }
            val search = PoiSearch(app, query)
            if (origin != null && origin.isValid()) {
                search.bound = PoiSearch.SearchBound(
                    LatLonPoint(origin.lat, origin.lng),
                    50_000,
                )
            }
            search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResult?, errorCode: Int) {
                    if (!cont.isActive) return
                    if (errorCode != 1000) {
                        cont.resumeWithException(
                            IllegalStateException(AmapError.searchMessage(errorCode, sha1, app.packageName)),
                        )
                        return
                    }
                    val items = result?.pois.orEmpty().map { it.toPoi(origin) }
                    cont.resume(items)
                }

                override fun onPoiItemSearched(item: PoiItem?, errorCode: Int) = Unit
            })
            search.searchPOIAsyn()
        }
    }

    suspend fun searchWalkPaths(from: GeoPoint, to: GeoPoint): List<WalkPath> {
        return suspendCancellableCoroutine { cont ->
            val fromAndTo = RouteSearch.FromAndTo(
                LatLonPoint(from.lat, from.lng),
                LatLonPoint(to.lat, to.lng),
            )
            val query = RouteSearch.WalkRouteQuery(fromAndTo, RouteSearch.WalkDefault).apply {
                runCatching { setExtensions(RouteSearch.EXTENSIONS_ALL) }
            }
            val routeSearch = RouteSearch(app)
            routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                override fun onWalkRouteSearched(result: WalkRouteResult?, errorCode: Int) {
                    if (!cont.isActive) return
                    if (errorCode != 1000) {
                        android.util.Log.e("AMapSearchClient", "walk route error=$errorCode")
                        cont.resumeWithException(
                            IllegalStateException(AmapError.searchMessage(errorCode, sha1, app.packageName)),
                        )
                        return
                    }
                    android.util.Log.i("AMapSearchClient", "walk route paths=${result?.paths.orEmpty().size}")
                    cont.resume(result?.paths.orEmpty())
                }

                override fun onBusRouteSearched(result: BusRouteResult?, errorCode: Int) = Unit
                override fun onDriveRouteSearched(result: DriveRouteResult?, errorCode: Int) = Unit
                override fun onRideRouteSearched(result: RideRouteResult?, errorCode: Int) = Unit
            })
            routeSearch.calculateWalkRouteAsyn(query)
        }
    }

    suspend fun searchBusPaths(from: GeoPoint, to: GeoPoint, city: String, destCity: String = city): List<BusPath> {
        val queryCity = city.trim()
        if (queryCity.isBlank()) {
            throw IllegalStateException("无法确定所在城市，请确认已开启定位后再规划公交")
        }
        return suspendCancellableCoroutine { cont ->
            val fromAndTo = RouteSearch.FromAndTo(
                LatLonPoint(from.lat, from.lng),
                LatLonPoint(to.lat, to.lng),
            )
            val query = RouteSearch.BusRouteQuery(fromAndTo, RouteSearch.BusDefault, queryCity, 0).apply {
                val other = destCity.trim()
                if (other.isNotBlank() && other != queryCity) setCityd(other)
                runCatching { setExtensions(RouteSearch.EXTENSIONS_ALL) }
            }
            val routeSearch = RouteSearch(app)
            routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                override fun onWalkRouteSearched(result: WalkRouteResult?, errorCode: Int) = Unit
                override fun onBusRouteSearched(result: BusRouteResult?, errorCode: Int) {
                    if (!cont.isActive) return
                    if (errorCode != 1000) {
                        android.util.Log.e("AMapSearchClient", "bus route error=$errorCode")
                        cont.resumeWithException(
                            IllegalStateException(AmapError.routeMessage(errorCode, sha1, app.packageName)),
                        )
                        return
                    }
                    val paths = result?.paths.orEmpty()
                    android.util.Log.i("AMapSearchClient", "bus route paths=${paths.size}")
                    cont.resume(paths)
                }
                override fun onDriveRouteSearched(result: DriveRouteResult?, errorCode: Int) = Unit
                override fun onRideRouteSearched(result: RideRouteResult?, errorCode: Int) = Unit
            })
            routeSearch.calculateBusRouteAsyn(query)
        }
    }

    suspend fun reverseGeocode(point: GeoPoint): String = reverseGeocodeDetails(point).address

    suspend fun reverseGeocodeDetails(point: GeoPoint): ReverseGeo {
        return suspendCancellableCoroutine { cont ->
            val geocodeSearch = GeocodeSearch(app)
            geocodeSearch.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                override fun onRegeocodeSearched(result: RegeocodeResult?, errorCode: Int) {
                    if (!cont.isActive) return
                    if (errorCode != 1000) {
                        cont.resume(ReverseGeo("", ""))
                        return
                    }
                    val addr = result?.regeocodeAddress
                    cont.resume(
                        ReverseGeo(
                            address = addr?.formatAddress.orEmpty(),
                            city = addr?.city.orEmpty().ifBlank { addr?.province.orEmpty() },
                        ),
                    )
                }

                override fun onGeocodeSearched(result: GeocodeResult?, errorCode: Int) = Unit
            })
            geocodeSearch.getFromLocationAsyn(
                RegeocodeQuery(LatLonPoint(point.lat, point.lng), 100f, GeocodeSearch.AMAP),
            )
        }
    }

    private fun PoiItem.toPoi(origin: GeoPoint?): Poi {
        val lat = latLonPoint?.latitude ?: 0.0
        val lng = latLonPoint?.longitude ?: 0.0
        val loc = GeoPoint(lat, lng)
        val distance = origin?.let { GeoMath.distanceMeters(it, loc).toInt() }
        return Poi(
            id = poiId.orEmpty(),
            name = title.orEmpty(),
            address = snippet.orEmpty().ifBlank { adName.orEmpty() },
            location = loc,
            distanceMeters = distance,
        )
    }
}
