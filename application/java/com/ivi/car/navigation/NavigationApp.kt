package com.ivi.car.navigation

import android.app.Application
import com.ivi.car.navigation.controller.NavigationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NavigationApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NavigationManager.initialize(this)
    }
}