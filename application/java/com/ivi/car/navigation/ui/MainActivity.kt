package com.ivi.car.navigation.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ivi.car.navigation.NavConstant
import com.ivi.car.navigation.R
import com.ivi.car.navigation.databinding.ActivityMainBinding
import com.ivi.car.navigation.service.NavigationService
import com.ivi.car.navigation.util.Utils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var navigationAssetsReady = false
    private var activityResumed = false
    companion object{
        @Volatile
        var isRunning= false
    }
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBackgroundVideo()
        lifecycleScope.launch(Dispatchers.IO) {
            Utils.copyNavigationAssets(this@MainActivity)
            withContext(Dispatchers.Main) {
                navigationAssetsReady = true
                if (activityResumed) {
                    addNaviFragment()
                }
            }
        }
    }

    private fun requestHideNavigationBar() {
        val controller = window.insetsController
        if (controller != null) {
            controller.show(WindowInsets.Type.statusBars())
            controller.hide(WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        isRunning = true
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        requestShowHideInfoCenter(NavConstant.DISMISS_INFO_CENTER)
        requestHideNavigationBar()
        if (navigationAssetsReady) {
            addNaviFragment()
        }
        if (binding.backgroundVideoView.isPlaying.not()) {
            binding.backgroundVideoView.start()
        }
    }

    /** Looping, muted background video - drop the file at res/raw/phud_dashboard_3840x208.mp4. */
    private fun setupBackgroundVideo() {
        val videoView = binding.backgroundVideoView
        videoView.setVideoURI(
            Uri.parse("android.resource://$packageName/${R.raw.phud_dashboard_3840x208}")
        )
        videoView.setOnPreparedListener { player ->
            player.isLooping = true
            player.setVolume(0f, 0f)
        }
        videoView.setOnErrorListener { _, what, extra ->
            android.util.Log.w(
                "MainActivity",
                "Background video playback error: what=$what, extra=$extra"
            )
            true
        }
    }

    private fun addNaviFragment() {
        if (supportFragmentManager.findFragmentById(R.id.navi_fragment) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.navi_fragment, NaviFragment())
                .commit()
        }
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
        requestShowHideInfoCenter(NavConstant.SHOW_INFO_CENTER)
        binding.backgroundVideoView.pause()
    }

    override fun onStop() {
        isRunning = false
        super.onStop()
    }

    fun ensureNavigationServiceRunning() {
        val navIntent = Intent(this, NavigationService::class.java)
        startForegroundService(navIntent)
    }

    fun stopNavigationService() {
        stopService(Intent(this, NavigationService::class.java))
    }

    private fun requestShowHideInfoCenter(action: String){
        val infoIntent = Intent(action)
        sendBroadcast(infoIntent)
    }
}