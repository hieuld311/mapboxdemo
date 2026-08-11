package com.ivi.car.navigation.repository

import android.content.Context
import com.ivi.car.navigation.model.HomeLocation
import com.mapbox.geojson.Point

class HomeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getHome(): HomeLocation? {
        if (!preferences.contains(KEY_LATITUDE) || !preferences.contains(KEY_LONGITUDE)) {
            return null
        }
        return HomeLocation(
            name = preferences.getString(KEY_NAME, DEFAULT_HOME_NAME) ?: DEFAULT_HOME_NAME,
            address = preferences.getString(KEY_ADDRESS, null),
            point = Point.fromLngLat(
                preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null,
                preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
            ),
            updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L)
        )
    }

    fun saveHome(home: HomeLocation) {
        preferences.edit()
            .putString(KEY_NAME, home.name)
            .putString(KEY_ADDRESS, home.address)
            .putString(KEY_LATITUDE, home.point.latitude().toString())
            .putString(KEY_LONGITUDE, home.point.longitude().toString())
            .putLong(KEY_UPDATED_AT, home.updatedAt)
            .apply()
    }

    fun clearHome() {
        preferences.edit()
            .remove(KEY_NAME)
            .remove(KEY_ADDRESS)
            .remove(KEY_LATITUDE)
            .remove(KEY_LONGITUDE)
            .remove(KEY_UPDATED_AT)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "navigation_home"
        private const val KEY_NAME = "name"
        private const val KEY_ADDRESS = "address"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val DEFAULT_HOME_NAME = "Home"
    }
}