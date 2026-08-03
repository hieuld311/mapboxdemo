package com.ivi.car.navigation.repository

import com.ivi.car.navigation.model.NavigationSearchResult
import com.ivi.car.navigation.model.NavigationSuggestion
import com.ivi.car.navigation.model.NavigationResultCode
import com.ivi.car.navigation.model.NearbyCategory
import com.ivi.car.navigation.model.SuggestionSort
import com.mapbox.geojson.Point
import com.mapbox.search.autocomplete.PlaceAutocomplete
import com.mapbox.search.autocomplete.PlaceAutocompleteOptions
import com.mapbox.search.autocomplete.PlaceAutocompleteSuggestion
import com.mapbox.search.discover.Discover
import com.mapbox.search.discover.DiscoverOptions
import com.mapbox.search.discover.DiscoverQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class NavigationSearchRepository(
    private val tripadvisorRepository: TripadvisorRepository
) {
    private val discover by lazy { Discover.create() }
    private val placeAutocomplete by lazy { PlaceAutocomplete.create() }
    private val autocompleteSuggestionsById =
        LinkedHashMap<String, PlaceAutocompleteSuggestion>()

    suspend fun resolveDestination(query: String): NavigationSuggestion? = withContext(Dispatchers.IO) {
        val suggestionsResponse = placeAutocomplete.suggestions(
            query = query,
            options = PlaceAutocompleteOptions()
        )
        val suggestions = if (suggestionsResponse.isValue) {
            suggestionsResponse.value.orEmpty()
        } else {
            emptyList()
        }
        val selected = suggestions.firstOrNull() ?: return@withContext null
        var resolved: NavigationSuggestion? = null
        placeAutocomplete.select(selected)
            .onValue { result ->
                val stableValue = "$query|${result.coordinate.longitude()}|${result.coordinate.latitude()}"
                resolved = NavigationSuggestion(
                    suggestionId = UUID.nameUUIDFromBytes(
                        stableValue.toByteArray(StandardCharsets.UTF_8)
                    ).toString(),
                    name = query,
                    address = null,
                    point = result.coordinate,
                    category = null,
                    distanceMeters = null,
                    rating = null,
                    reviewCount = null,
                    ratingSource = null,
                    photoUrl = null,
                    detailsUrl = null,
                    source = SOURCE_MAPBOX
                )
            }
        resolved
    }

    suspend fun searchDestinationSuggestions(
        query: String,
        limit: Int,
        origin: Point?
    ): NavigationSearchResult = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, MAX_RESULTS)
        val suggestionsResponse = placeAutocomplete.suggestions(
            query = query,
            options = PlaceAutocompleteOptions()
        )
        val suggestions = if (suggestionsResponse.isValue) {
            suggestionsResponse.value.orEmpty().take(safeLimit)
        } else {
            emptyList()
        }
        synchronized(autocompleteSuggestionsById) {
            autocompleteSuggestionsById.clear()
        }
        val resolved = suggestions.mapNotNull { suggestion ->
            val coordinate = suggestion.coordinate ?: return@mapNotNull null
            val stableValue = buildString {
                append(suggestion.name)
                append('|')
                append(suggestion.formattedAddress)
                append('|')
                append(coordinate.longitude())
                append('|')
                append(coordinate.latitude())
            }
            val suggestionId = UUID.nameUUIDFromBytes(
                stableValue.toByteArray(StandardCharsets.UTF_8)
            ).toString()
            synchronized(autocompleteSuggestionsById) {
                autocompleteSuggestionsById[suggestionId] = suggestion
            }
            NavigationSuggestion(
                suggestionId = suggestionId,
                name = suggestion.name,
                address = suggestion.formattedAddress,
                point = coordinate,
                category = null,
                distanceMeters = suggestion.distanceMeters ?: origin?.let {
                    haversineMeters(it, coordinate)
                },
                rating = null,
                reviewCount = null,
                ratingSource = null,
                photoUrl = null,
                detailsUrl = null,
                source = SOURCE_MAPBOX_AUTOCOMPLETE
            )
        }
        NavigationSearchResult(
            resultCode = if (resolved.isEmpty()) {
                NavigationResultCode.NOT_FOUND
            } else {
                NavigationResultCode.ACCEPTED
            },
            category = null,
            sortBy = null,
            candidates = resolved,
            message = if (resolved.isEmpty()) "No destination suggestions found" else null
        )
    }

    fun hasAutocompleteSuggestion(suggestionId: String): Boolean {
        return synchronized(autocompleteSuggestionsById) {
            autocompleteSuggestionsById.containsKey(suggestionId)
        }
    }

    suspend fun loadSuggestionDetails(
        suggestion: NavigationSuggestion
    ): NavigationSuggestion = tripadvisorRepository.loadRatingAndPhoto(suggestion)

    suspend fun confirmAutocompleteSuggestion(suggestionId: String): Point? =
        withContext(Dispatchers.IO) {
            val suggestion = synchronized(autocompleteSuggestionsById) {
                autocompleteSuggestionsById[suggestionId]
            } ?: return@withContext null
            var coordinate: Point? = null
            placeAutocomplete.select(suggestion).onValue { result ->
                coordinate = result.coordinate
            }
            coordinate
        }

    suspend fun searchNearby(
        category: NearbyCategory,
        limit: Int,
        sortBy: SuggestionSort?,
        origin: Point
    ): NavigationSearchResult = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, MAX_RESULTS)
        var discovered = emptyList<com.mapbox.search.discover.DiscoverResult>()
        var searchError: Throwable? = null
        discover.search(
            query = DiscoverQuery.Category.create(category.mapboxName),
            proximity = origin,
            options = DiscoverOptions(limit = safeLimit)
        ).onValue { values ->
            discovered = values
        }.onError { error ->
            searchError = error
        }
        if (searchError != null) {
            return@withContext NavigationSearchResult(
                resultCode = NavigationResultCode.INTERNAL_ERROR,
                category = category,
                sortBy = sortBy,
                candidates = emptyList(),
                message = searchError?.message ?: "Nearby search failed"
            )
        }

        val mapboxSuggestions = discovered.map { result ->
            val stableValue = buildString {
                append(category.name)
                append('|')
                append(result.name)
                append('|')
                append(result.coordinate.longitude())
                append('|')
                append(result.coordinate.latitude())
            }
            NavigationSuggestion(
                suggestionId = UUID.nameUUIDFromBytes(
                    stableValue.toByteArray(StandardCharsets.UTF_8)
                ).toString(),
                name = result.name,
                address = result.address.formattedAddress,
                point = result.coordinate,
                category = category,
                distanceMeters = haversineMeters(origin, result.coordinate),
                rating = null,
                reviewCount = null,
                ratingSource = null,
                photoUrl = null,
                detailsUrl = null,
                source = SOURCE_MAPBOX
            )
        }
        // The normal in-app flow shows Mapbox candidates immediately and enriches only
        // the selected detail. Rating enrichment stays here exclusively for the agent's
        // explicit rating-sort request.
        val candidates = if (sortBy == SuggestionSort.RATING) {
            tripadvisorRepository.enrichRatings(mapboxSuggestions)
        } else {
            mapboxSuggestions
        }
        val sorted = when (sortBy) {
            null -> candidates
            SuggestionSort.DISTANCE -> candidates.sortedBy {
                it.distanceMeters ?: Double.MAX_VALUE
            }
            SuggestionSort.RATING -> candidates.sortedWith(
                compareByDescending<NavigationSuggestion> { it.rating != null }
                    .thenByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.distanceMeters ?: Double.MAX_VALUE }
            )
        }
        NavigationSearchResult(
            resultCode = if (sorted.isEmpty()) {
                NavigationResultCode.NOT_FOUND
            } else {
                NavigationResultCode.ACCEPTED
            },
            category = category,
            sortBy = sortBy,
            candidates = sorted.take(safeLimit),
            message = if (sorted.isEmpty()) "No nearby candidates found" else null
        )
    }

    private fun haversineMeters(first: Point, second: Point): Double {
        val earthRadiusMeters = 6_371_000.0
        val firstLatitude = Math.toRadians(first.latitude())
        val secondLatitude = Math.toRadians(second.latitude())
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(second.longitude() - first.longitude())
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(firstLatitude) * cos(secondLatitude) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return earthRadiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        private const val SOURCE_MAPBOX = "MAPBOX"
        private const val SOURCE_MAPBOX_AUTOCOMPLETE = "MAPBOX_AUTOCOMPLETE"
        private const val MAX_RESULTS = 20
    }
}
