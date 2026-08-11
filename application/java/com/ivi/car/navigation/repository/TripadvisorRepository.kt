package com.ivi.car.navigation.repository

import android.content.Context
import com.ivi.car.navigation.model.NavigationSuggestion
import com.ivi.car.navigation.model.NearbyCategory
import com.ivi.car.navigation.util.GeoUtils
import com.mapbox.geojson.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class TripadvisorRepository(
    private val context: Context
) {
    suspend fun enrichRatings(
        suggestions: List<NavigationSuggestion>
    ): List<NavigationSuggestion> = coroutineScope {
        val apiKey = apiKeyOrNull() ?: return@coroutineScope suggestions
        suggestions.map { suggestion ->
            async(Dispatchers.IO) {
                if (suggestion.category != null && suggestion.category !in SUPPORTED_CATEGORIES) {
                    suggestion
                } else {
                    runCatching { findMatch(apiKey, suggestion)?.suggestion }
                        .getOrNull() ?: suggestion
                }
            }
        }.awaitAll()
    }

    suspend fun loadRatingAndPhoto(
        suggestion: NavigationSuggestion
    ): NavigationSuggestion {
        if (suggestion.category != null && suggestion.category !in SUPPORTED_CATEGORIES) {
            return suggestion
        }
        val apiKey = apiKeyOrNull() ?: return suggestion
        return withContext(Dispatchers.IO) {
            val match = runCatching { findMatch(apiKey, suggestion) }.getOrNull()
                ?: return@withContext suggestion
            val photoUrl = runCatching {
                loadFirstPhotoUrl(apiKey, match.locationId)
            }.getOrNull()
            match.suggestion.copy(photoUrl = photoUrl)
        }
    }

    private fun findMatch(apiKey: String, suggestion: NavigationSuggestion): TripadvisorMatch? {
        val body = requestJson(
            path = "/locations/search",
            apiKey = apiKey,
            parameters = mapOf(
                "version" to "1",
                "query" to suggestion.name,
                "search_type" to "NAME",
                "locale" to "en-US",
                "page" to "1",
                "size" to "10"
            )
        )
        val data = JSONObject(body).optJSONArray("data") ?: return null
        var bestMatch: Pair<JSONObject, Double>? = null
        for (index in 0 until data.length()) {
            val location = data.optJSONObject(index)?.optJSONObject("location") ?: continue
            val coordinates = location.optJSONObject("coordinates") ?: continue
            if (!coordinates.has("latitude") || !coordinates.has("longitude")) continue
            val point = Point.fromLngLat(
                coordinates.getDouble("longitude"),
                coordinates.getDouble("latitude")
            )
            val distance = GeoUtils.haversineMeters(suggestion.point, point)
            if (distance <= MAX_MATCH_DISTANCE_METERS &&
                (bestMatch == null || distance < requireNotNull(bestMatch).second)
            ) {
                bestMatch = location to distance
            }
        }
        val location = bestMatch?.first ?: return null
        val locationId = location.optString("id")
            .ifBlank { location.optString("location_id") }
            .ifBlank { location.optString("locationId") }
            .takeIf { it.isNotBlank() }
            ?: return null
        val rating = location.optJSONObject("traveler_ratings")?.optJSONObject("overall")
        val detailsUrl = location.optJSONObject("urls")
            ?.optJSONObject("tripadvisor")
            ?.optString("main")
            ?.ifBlank { null }
        return TripadvisorMatch(
            locationId = locationId,
            suggestion = suggestion.copy(
                rating = rating?.optDoubleOrNull("rating"),
                reviewCount = rating?.optIntOrNull("count"),
                ratingSource = SOURCE_TRIPADVISOR,
                detailsUrl = detailsUrl,
                source = suggestion.source.withTripadvisorSource()
            )
        )
    }

    private fun loadFirstPhotoUrl(apiKey: String, locationId: String): String? {
        val body = requestJson(
            path = "/locations/$locationId/photos",
            apiKey = apiKey,
            parameters = mapOf("page" to "1", "size" to "1")
        )
        val root = JSONObject(body)
        val photos = root.optJSONArray("data") ?: root.optJSONArray("photos") ?: return null
        return findImageUrl(photos.opt(0))
    }

    private fun findImageUrl(value: Any?, depth: Int = 0): String? {
        if (depth > 5) return null
        return when (value) {
            is String -> value.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            is JSONObject -> {
                val preferredKeys = arrayOf(
                    "url", "original", "large", "medium", "dynamic_urls", "images"
                )
                preferredKeys.firstNotNullOfOrNull { key ->
                    findImageUrl(value.opt(key), depth + 1)
                } ?: value.keys().asSequence().firstNotNullOfOrNull { key ->
                    findImageUrl(value.opt(key), depth + 1)
                }
            }
            is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { index ->
                findImageUrl(value.opt(index), depth + 1)
            }
            else -> null
        }
    }

    private fun requestJson(
        path: String,
        apiKey: String,
        parameters: Map<String, String>
    ): String {
        val query = parameters.entries.joinToString("&") { (name, value) ->
            "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val connection = (URL("$BASE_URL$path?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-API-Key", apiKey)
        }
        try {
            val responseCode = connection.responseCode
            val body = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            if (responseCode !in 200..299) {
                error("Tripadvisor request failed ($responseCode): ${body.take(180)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun apiKeyOrNull(): String? {
        val resourceId = context.resources.getIdentifier(
            "tripadvisor_api_key",
            "string",
            context.packageName
        )
        if (resourceId == 0) return null
        return context.getString(resourceId)
            .trim()
            .takeIf { it.isNotBlank() && it != "YOUR_TRIPADVISOR_API_KEY" }
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getDouble(name) }.getOrNull()
    }

    private fun JSONObject.optIntOrNull(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getInt(name) }.getOrNull()
    }

    private fun String.withTripadvisorSource(): String =
        if (contains(SOURCE_TRIPADVISOR)) this else "$this+$SOURCE_TRIPADVISOR"

    companion object {
        private const val BASE_URL = "https://terra.tripadvisor.com/api"
        private const val SOURCE_TRIPADVISOR = "TRIPADVISOR_TERRA"
        private const val MAX_MATCH_DISTANCE_METERS = 750.0
        private val SUPPORTED_CATEGORIES = setOf(
            NearbyCategory.HOTEL,
            NearbyCategory.HOSPITAL,
            NearbyCategory.RESTAURANT,
            NearbyCategory.GAS_STATION,
            NearbyCategory.CONVENIENCE_STORE
        )
    }

    private data class TripadvisorMatch(
        val locationId: String,
        val suggestion: NavigationSuggestion
    )
}