package com.ivi.car.navigation.model

import com.google.gson.annotations.SerializedName
import com.mapbox.geojson.Point

data class SearchPlace(
    @SerializedName("id")
    val id: String,
    @SerializedName("name")
    val name: String?,
    @SerializedName("descriptionText")
    val descriptionText: String?,
    @SerializedName("address")
    val address: String?,
    @SerializedName("coordinate")
    val coordinate: Point,
    @SerializedName("distanceMeters")
    val distanceMeters: Double?,
    @SerializedName("rating")
    val rating: Double? = null,
    @SerializedName("reviewCount")
    val reviewCount: Int? = null,
    @SerializedName("ratingSource")
    val ratingSource: String? = null
)
