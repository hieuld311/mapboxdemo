package com.ivi.car.navigation.di

import android.content.Context
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.formatter.UnitType
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NavigationModule {

    @Provides
    @Singleton
    fun provieDistanceFormatterOptions(@ApplicationContext context: Context): DistanceFormatterOptions{
        return DistanceFormatterOptions.Builder(context).unitType(UnitType.METRIC).build()
    }

    @Provides
    @Singleton
    fun provideDistanceFormatter(options: DistanceFormatterOptions): MapboxDistanceFormatter {
        return MapboxDistanceFormatter(options)
    }

    @Provides
    @Singleton
    fun provideManeuverApi(distanceFormatter: MapboxDistanceFormatter): MapboxManeuverApi{
        return MapboxManeuverApi(distanceFormatter)
    }
}