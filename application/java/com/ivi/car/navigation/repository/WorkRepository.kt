package com.ivi.car.navigation.repository

import android.content.Context
import com.ivi.car.navigation.model.WorkLocation
import com.mapbox.geojson.Point

class WorkRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getWork(): WorkLocation? {
        if (!preferences.contains(KEY_LATITUDE) || !preferences.contains(KEY_LONGITUDE)) {
            return null
        }
        return WorkLocation(
            name = preferences.getString(KEY_NAME, DEFAULT_WORK_NAME) ?: DEFAULT_WORK_NAME,
            address = preferences.getString(KEY_ADDRESS, null),
            point = Point.fromLngLat(
                preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null,
                preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
            ),
            updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L)
        )
    }

    fun saveWork(work: WorkLocation) {
        preferences.edit()
            .putString(KEY_NAME, work.name)
            .putString(KEY_ADDRESS, work.address)
            .putString(KEY_LATITUDE, work.point.latitude().toString())
            .putString(KEY_LONGITUDE, work.point.longitude().toString())
            .putLong(KEY_UPDATED_AT, work.updatedAt)
            .apply()
    }

    fun clearWork() {
        preferences.edit()
            .remove(KEY_NAME)
            .remove(KEY_ADDRESS)
            .remove(KEY_LATITUDE)
            .remove(KEY_LONGITUDE)
            .remove(KEY_UPDATED_AT)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "navigation_work"
        private const val KEY_NAME = "name"
        private const val KEY_ADDRESS = "address"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val DEFAULT_WORK_NAME = "Work"
    }
}