package com.ivi.car.navigation.model

import com.mapbox.geojson.Point
import org.json.JSONArray
import org.json.JSONObject

object NavigationResultCode {
    const val ACCEPTED = 0
    const val INVALID_ARGUMENT = -1
    const val UNAVAILABLE = -2
    const val INVALID_STATE = -3
    const val LOCATION_UNAVAILABLE = -4
    const val NOT_FOUND = -5
    const val HOME_NOT_CONFIGURED = -6
    const val INTERNAL_ERROR = -7
    const val WORK_NOT_CONFIGURED = -8
}

enum class NavigationStatus {
    IDLE,
    SHOWING_SUGGESTIONS,
    ROUTE_CALCULATING,
    ROUTE_SET,
    SIMULATING_DRIVE,
    UNAVAILABLE
}

enum class NearbyCategory(val code: Int, val mapboxName: String) {
    HOTEL(1, "hotel"),
    HOSPITAL(2, "hospital"),
    RESTAURANT(3, "restaurants"),
    GAS_STATION(4, "gas_station"),
    CONVENIENCE_STORE(5, "groceries");

    companion object {
        fun fromCode(code: Int): NearbyCategory? = values().firstOrNull { it.code == code }

        fun fromName(value: String): NearbyCategory? {
            val normalized = value.trim().uppercase().replace(' ', '_').replace('-', '_')
            return values().firstOrNull {
                it.name == normalized || it.mapboxName.uppercase() == normalized
            }
        }
    }
}

enum class SuggestionSort(val code: Int) {
    DISTANCE(1),
    RATING(2);

    companion object {
        fun fromCode(code: Int): SuggestionSort? = values().firstOrNull { it.code == code }
    }
}

enum class MapStyleMode(val code: Int) {
    NORMAL(1),
    STANDARD_3D(2),
    TRAFFIC(3),
    LIGHT(4),
    SATELLITE(5);

    companion object {
        fun fromCode(code: Int): MapStyleMode? = values().firstOrNull { it.code == code }
    }
}

data class NavigationSuggestion(
    val suggestionId: String,
    val name: String,
    val address: String?,
    val point: Point,
    val category: NearbyCategory?,
    val distanceMeters: Double?,
    val rating: Double?,
    val reviewCount: Int?,
    val ratingSource: String?,
    val photoUrl: String?,
    val detailsUrl: String?,
    val source: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("suggestionId", suggestionId)
        .put("name", name)
        .put("address", address)
        .put("latitude", point.latitude())
        .put("longitude", point.longitude())
        .put("category", category?.name)
        .put("distanceMeters", distanceMeters)
        .put("rating", rating)
        .put("reviewCount", reviewCount)
        .put("ratingSource", ratingSource)
        .put("photoUrl", photoUrl)
        .put("detailsUrl", detailsUrl)
        .put("source", source)
}

data class NavigationSearchResult(
    val resultCode: Int,
    val category: NearbyCategory?,
    val sortBy: SuggestionSort?,
    val candidates: List<NavigationSuggestion>,
    val message: String? = null
) {
    fun toJson(): String {
        val items = JSONArray()
        candidates.forEach { items.put(it.toJson()) }
        return JSONObject()
            .put("resultCode", resultCode)
            .put("category", category?.name ?: JSONObject.NULL)
            .put("sortBy", sortBy?.name ?: JSONObject.NULL)
            .put("count", candidates.size)
            .put("candidates", items)
            .put("message", message)
            .toString()
    }
}

data class HomeLocation(
    val name: String,
    val address: String?,
    val point: Point,
    val updatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("address", address)
        .put("latitude", point.latitude())
        .put("longitude", point.longitude())
        .put("updatedAt", updatedAt)
}

data class WorkLocation(
    val name: String,
    val address: String?,
    val point: Point,
    val updatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("address", address)
        .put("latitude", point.latitude())
        .put("longitude", point.longitude())
        .put("updatedAt", updatedAt)
}

data class NavigationState(
    val status: NavigationStatus = NavigationStatus.IDLE,
    val destination: NavigationSuggestion? = null,
    val suggestions: List<NavigationSuggestion> = emptyList(),
    val home: HomeLocation? = null,
    val work: WorkLocation? = null,
    val mapStyle: MapStyleMode = MapStyleMode.NORMAL,
    val distanceRemainingMeters: Double? = null,
    val durationRemainingSeconds: Int? = null,
    val message: String? = null,
    val version: Long = 0
) {
    fun toJson(): String {
        val items = JSONArray()
        suggestions.forEach { items.put(it.toJson()) }
        return JSONObject()
            .put("status", status.name)
            .put("destination", destination?.toJson())
            .put("suggestions", items)
            .put("home", home?.toJson())
            .put("work", work?.toJson())
            .put("mapStyle", mapStyle.name)
            .put("distanceRemainingMeters", distanceRemainingMeters)
            .put("durationRemainingSeconds", durationRemainingSeconds)
            .put("message", message)
            .put("version", version)
            .toString()
    }
}
