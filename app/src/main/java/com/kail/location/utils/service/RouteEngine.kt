package com.kail.location.utils.service

import com.baidu.mapapi.model.LatLng
import com.kail.location.geo.GeoMath
import com.kail.location.utils.MapUtils

/**
 * 负责路线点管理、沿路线前进、距离计算与进度汇报。
 */
class RouteEngine {

    private val routePoints: MutableList<Pair<Double, Double>> = mutableListOf()
    private val routeCumulativeDistances: MutableList<Double> = mutableListOf()
    private var totalDistance: Double = 0.0
    private var routeIndex = 0
    private var routeLoop = false
    private var segmentProgressMeters = 0.0

    var currentLng: Double = 0.0
    var currentLat: Double = 0.0
    var currentBea: Float = 0.0f

    val isActive: Boolean get() = routePoints.size >= 2
    val progressRatio: Float
        get() {
            if (totalDistance <= 0) return 0f
            val currentDist = if (routeIndex < routeCumulativeDistances.size)
                routeCumulativeDistances[routeIndex] + segmentProgressMeters
            else totalDistance
            return (currentDist / totalDistance).toFloat().coerceIn(0f, 1f)
        }

    fun setupFromArray(routeArray: DoubleArray, coordType: String) {
        routePoints.clear()
        routeCumulativeDistances.clear()
        var i = 0
        while (i + 1 < routeArray.size) {
            val lng = routeArray[i]
            val lat = routeArray[i + 1]
            when (coordType) {
                ServiceConstants.COORD_WGS84 -> routePoints.add(Pair(lng, lat))
                ServiceConstants.COORD_GCJ02 -> {
                    val wgs = MapUtils.gcj02towgs84(lng, lat)
                    routePoints.add(Pair(wgs[0], wgs[1]))
                }
                else -> {
                    val wgs = MapUtils.bd2wgs(lng, lat)
                    routePoints.add(Pair(wgs[0], wgs[1]))
                }
            }
            i += 2
        }
        routeIndex = 0
        segmentProgressMeters = 0.0
        calculateRouteDistances()
    }

    fun setLoop(loop: Boolean) {
        routeLoop = loop
    }

    fun clear() {
        routePoints.clear()
        routeCumulativeDistances.clear()
        totalDistance = 0.0
        routeIndex = 0
        segmentProgressMeters = 0.0
    }

    fun seekToRatio(ratio: Float) {
        if (routePoints.size < 2 || routeCumulativeDistances.isEmpty()) return
        val targetDist = totalDistance * ratio.coerceIn(0f, 1f)
        var idx = 0
        for (i in 0 until routeCumulativeDistances.size - 1) {
            if (targetDist >= routeCumulativeDistances[i] && targetDist < routeCumulativeDistances[i + 1]) {
                idx = i
                break
            }
        }
        if (targetDist >= totalDistance) {
            idx = routePoints.size - 2
        }
        routeIndex = idx
        segmentProgressMeters = targetDist - routeCumulativeDistances[idx]

        val a = routePoints[routeIndex]
        val b = routePoints[(routeIndex + 1).coerceAtMost(routePoints.size - 1)]
        val segLen = segmentLengthMeters(a, b)
        val f = if (segLen > 0) (segmentProgressMeters / segLen) else 0.0
        val dLngDeg = b.first - a.first
        val dLatDeg = b.second - a.second
        currentLng = a.first + dLngDeg * f
        currentLat = a.second + dLatDeg * f
        currentBea = GeoMath.bearingDegrees(a.first, a.second, b.first, b.second)
    }

    fun advance(distanceMeters: Double) {
        var remaining = distanceMeters
        while (remaining > 0 && routePoints.size >= 2) {
            val startIdx = routeIndex
            val endIdx = if (startIdx + 1 < routePoints.size) startIdx + 1 else -1
            if (endIdx == -1) {
                if (routeLoop) {
                    routeIndex = 0
                    segmentProgressMeters = 0.0
                    continue
                } else {
                    clear()
                    break
                }
            }
            val a = routePoints[startIdx]
            val b = routePoints[endIdx]
            val segLen = segmentLengthMeters(a, b)
            if (segLen <= 0.0) {
                routeIndex++
                segmentProgressMeters = 0.0
                if (routeIndex >= routePoints.size - 1) {
                    if (routeLoop) {
                        routeIndex = 0
                    } else {
                        clear()
                        break
                    }
                }
                continue
            }
            val available = segLen - segmentProgressMeters
            if (remaining >= available) {
                currentLng = b.first
                currentLat = b.second
                currentBea = GeoMath.bearingDegrees(a.first, a.second, b.first, b.second)
                remaining -= available
                routeIndex++
                segmentProgressMeters = 0.0
                if (routeIndex >= routePoints.size - 1) {
                    if (routeLoop) {
                        routeIndex = 0
                    } else {
                        clear()
                        break
                    }
                }
            } else {
                segmentProgressMeters += remaining
                val f = segmentProgressMeters / segLen
                val dLngDeg = b.first - a.first
                val dLatDeg = b.second - a.second
                currentLng = a.first + dLngDeg * f
                currentLat = a.second + dLatDeg * f
                currentBea = GeoMath.bearingDegrees(a.first, a.second, b.first, b.second)
                remaining = 0.0
            }
        }
    }

    fun buildStatusString(): Pair<String, LatLng>? {
        if (routePoints.isEmpty()) return null
        val currentDist = if (routeIndex < routeCumulativeDistances.size)
            routeCumulativeDistances[routeIndex] + segmentProgressMeters
        else totalDistance

        val distStr = if (currentDist > 1000) String.format("%.2fkm", currentDist / 1000) else String.format("%.0fm", currentDist)
        val totalDistStr = if (totalDistance > 1000) String.format("%.2fkm", totalDistance / 1000) else String.format("%.0fm", totalDistance)
        val bd = MapUtils.wgs2bd(currentLng, currentLat)
        return "$distStr / $totalDistStr" to LatLng(bd[1], bd[0])
    }

    private fun calculateRouteDistances() {
        routeCumulativeDistances.clear()
        routeCumulativeDistances.add(0.0)
        var total = 0.0
        for (i in 0 until routePoints.size - 1) {
            val a = routePoints[i]
            val b = routePoints[i + 1]
            total += segmentLengthMeters(a, b)
            routeCumulativeDistances.add(total)
        }
        totalDistance = total
    }

    private fun segmentLengthMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val midLat = (a.second + b.second) / 2.0
        val dLatDeg = b.second - a.second
        val dLngDeg = b.first - a.first
        val metersPerDegLat = GeoMath.metersPerDegLat(midLat)
        val metersPerDegLng = GeoMath.metersPerDegLng(midLat)
        return kotlin.math.sqrt(
            (dLatDeg * metersPerDegLat) * (dLatDeg * metersPerDegLat) +
            (dLngDeg * metersPerDegLng) * (dLngDeg * metersPerDegLng)
        )
    }
}
