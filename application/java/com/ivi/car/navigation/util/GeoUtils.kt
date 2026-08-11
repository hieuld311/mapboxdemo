package com.ivi.car.navigation.util

import com.mapbox.geojson.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun haversineMeters(first: Point, second: Point): Double {
        val firstLatitude = Math.toRadians(first.latitude())
        val secondLatitude = Math.toRadians(second.latitude())
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(second.longitude() - first.longitude())
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
                cos(firstLatitude) * cos(secondLatitude) *
                sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
