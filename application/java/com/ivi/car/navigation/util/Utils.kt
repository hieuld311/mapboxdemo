package com.ivi.car.navigation.util

import android.car.cluster.navigation.NavigationState
import android.util.Log
import com.ivi.car.navigation.model.Dtd
import com.ivi.car.navigation.model.Eta
import com.ivi.car.navigation.model.Navigation
import com.ivi.car.navigation.model.TurnByTurn
import kotlin.math.floor
import android.content.Context
import android.content.res.AssetManager
import fauto.car.sharedata.FAutoShareDataManager
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.round

object Utils {
    fun copyNavigationAssets(context: Context) {
        val targetNavFolder = File(context.filesDir, "mbx_nav/tiles/navigation")
        val mapDataDestFolder = File(context.filesDir, ".mapbox/map_data")
        val completionMarker = File(context.filesDir, ".navigation_assets_complete")

        if (completionMarker.exists()) {
            return
        }
        targetNavFolder.mkdirs()
        mapDataDestFolder.mkdirs()
        val navigationCopied =
            copyAssetsRecursively(context.assets, "navigation", targetNavFolder)
        val mapDataCopied =
            copyAssetsRecursively(context.assets, "map_data", mapDataDestFolder)
        if (navigationCopied && mapDataCopied) {
            runCatching { completionMarker.createNewFile() }
                .onFailure { Log.e("Utils", "Unable to mark navigation assets complete", it) }
        }
    }

    private fun copyAssetsRecursively(
        assetManager: AssetManager, assetPath: String, targetDir: File
    ): Boolean {
        return try {
            val items = assetManager.list(assetPath) ?: arrayOf()

            if (items.isEmpty()) {
                copyAssetFile(assetManager, assetPath, targetDir)
            } else {
                if (!targetDir.exists()) targetDir.mkdirs()
                items.all { item ->
                    val childAssetPath = "$assetPath/$item"
                    val childTarget = File(targetDir, item)
                    copyAssetsRecursively(assetManager, childAssetPath, childTarget)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    private fun copyAssetFile(
        assetManager: AssetManager,
        assetPath: String,
        targetFile: File
    ): Boolean {
        return try {
            assetManager.open(assetPath).use { input: InputStream ->
                FileOutputStream(targetFile).use { output: OutputStream ->
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    fun convertDataToTurnByTurn(navigation: Navigation): TurnByTurn {
        val mTurnByTurn: TurnByTurn = TurnByTurn()
        val maneuverType = navigation.getType()
        mTurnByTurn.maneuvererID = maneuverType?.number ?: 0
        if (navigation.getStepDistance() >= 1000) {
            mTurnByTurn.distanceToTurnPoint =
                convertMetersToKilometers(navigation.getStepDistance())
            mTurnByTurn.distanceToTurnPointTxt =
                convertMetersToKilometers(navigation.getStepDistance()).toString()
            mTurnByTurn.distanceToTurnPointUnit =
                NavigationState.Distance.Unit.KILOMETERS.number
            mTurnByTurn.distanceToTurnPointUnitTxt =
                convertUnitTxt(NavigationState.Distance.Unit.KILOMETERS)
        } else {
            mTurnByTurn.distanceToTurnPoint = Math.round(navigation.getStepDistance()).toDouble()
            mTurnByTurn.distanceToTurnPointTxt = Math.round(navigation.getStepDistance()).toString()
            mTurnByTurn.distanceToTurnPointUnit =
                NavigationState.Distance.Unit.METERS.number
            mTurnByTurn.distanceToTurnPointUnitTxt =
                convertUnitTxt(NavigationState.Distance.Unit.METERS)
        }
        mTurnByTurn.turnSide = 4
        mTurnByTurn.turnPointName = navigation.getStepRoad()
        mTurnByTurn.type = maneuverType?.name
        return mTurnByTurn
    }

    fun normalizeData(navigation: Navigation): Navigation {
        val normalized = Navigation().apply {
            setCurrentRoad(navigation.getCurrentRoad())
            setDestination(navigation.getDestination())
            setDuration(navigation.getDuration())
            setDistance(navigation.getDistance())
            setDistanceUnit(navigation.getDistanceUnit())
            setStepRoad(navigation.getStepRoad())
            setStepDuration(navigation.getStepDuration())
            setCue(navigation.getCue())
            setLane(navigation.getLane())
            setType(navigation.getType())
        }
        if (navigation.getStepDistance() >= 1000) {
            normalized.setStepDistance(convertMetersToKilometers(navigation.getStepDistance()))
            normalized.setStepUnit(NavigationState.Distance.Unit.KILOMETERS)
        } else {
            normalized.setStepDistance(Math.round(navigation.getStepDistance()).toDouble())
            normalized.setStepUnit(NavigationState.Distance.Unit.METERS)
        }
        return normalized
    }

    fun updateNavigation(
        navigation: Navigation,
        distanceRemaining: Double,
        durationRemaining: Int,
        stepRoad: String?,
        stepDistanceRemaining: Double?,
        maneuverType: String?,
        maneuverModifier: String?
    ): Navigation {
        navigation.setDistance(distanceRemaining)
        navigation.setDuration(durationRemaining)
        if (stepRoad != null) {
            navigation.setStepRoad(stepRoad)
        }
        if (stepDistanceRemaining != null) {
            navigation.setStepDistance(stepDistanceRemaining)
        }
        if (maneuverType != null) {
            navigation.setType(
                NavigationState.Maneuver.Type.forNumber(
                    getType("$maneuverType ${maneuverModifier.orEmpty()}".trim())
                )
            )
        }
        // Lane data must come from the upcoming intersection. Do not send a
        // fixed lane shape when Mapbox has not supplied lane guidance.
        navigation.setLane(null)
        return navigation
    }

    fun convertDataToEta(navigation: Navigation): Eta {
        val mEta: Eta = Eta()
        mEta.timeInMinutes = navigation.getDuration() / 60
        return mEta
    }

    fun convertDataToDtd(navigation: Navigation): Dtd {
        val mDtd: Dtd = Dtd()
        if (navigation.getDistance() >= 1000) {
            mDtd.distanceToDestination = convertMetersToKilometers(navigation.getDistance())
            mDtd.distanceUnit = NavigationState.Distance.Unit.KILOMETERS.number
        } else {
            mDtd.distanceToDestination = floor(navigation.getDistance() / 50) * 50
            mDtd.distanceUnit = NavigationState.Distance.Unit.METERS.number
        }
        return mDtd
    }

    private fun convertMetersToKilometers(meters: Double): Double {
        return round((meters / 1000.0) * 10.0) / 10.0
    }


    private fun convertUnitTxt(unit: NavigationState.Distance.Unit): String {
        return when (unit) {
            NavigationState.Distance.Unit.METERS -> "m"
            NavigationState.Distance.Unit.KILOMETERS -> "km"
            NavigationState.Distance.Unit.MILES -> "mi"
            NavigationState.Distance.Unit.FEET -> "ft"
            else -> "unknown"
        }
    }

    fun getType(type: String?): Int {
        return when (type) {
            "" -> 0
            "depart" -> 1
            "name_change" -> 2
            "keep_left" -> 3
            "keep_right" -> 4
            "turn slight left" -> 5
            "turn slight right" -> 6
            "turn left" -> 7
            "turn right" -> 8
            "sharp left" -> 9
            "sharp right" -> 10
            "u_turn_left" -> 57
            "u-turn", "turn uturn" -> 11
            "u_turn_right" -> 12
            "on_ramp_slight_left" -> 13
            "on_ramp_slight_right" -> 14
            "on_ramp_normal_left" -> 15
            "on_ramp_normal_right" -> 16
            "on_ramp_sharp_left" -> 17
            "on_ramp_sharp_right" -> 18
            "on_ramp_u_turn_left" -> 19
            "on_ramp_u_turn_right" -> 20
            "off_ramp_slight_left" -> 21
            "off_ramp_slight_right" -> 22
            "off_ramp_normal_left" -> 23
            "off_ramp_normal_right" -> 24
            "fork_left" -> 25
            "fork_right" -> 26
            "merge_left" -> 27
            "merge_right" -> 28
            "merge_side_unspecified" -> 54
            "enter roundabout", "roundabout left" -> 29
            "roundabout_exit" -> 30
            "roundabout_enter_and_exit_cw" -> 55
            "roundabout_enter_and_exit_cw_sharp_right" -> 31
            "roundabout_enter_and_exit_cw_normal_right" -> 32
            "roundabout_enter_and_exit_cw_slight_right" -> 33
            "roundabout_enter_and_exit_cw_straight" -> 34
            "roundabout_enter_and_exit_cw_sharp_left" -> 35
            "roundabout_enter_and_exit_cw_normal_left" -> 36
            "roundabout_enter_and_exit_cw_slight_left" -> 37
            "roundabout_enter_and_exit_cw_u_turn" -> 38
            "roundabout_enter_and_exit_ccw" -> 56
            "roundabout_enter_and_exit_ccw_sharp_right" -> 39
            "roundabout_enter_and_exit_ccw_normal_right" -> 40
            "roundabout_enter_and_exit_ccw_slight_right" -> 41
            "roundabout_enter_and_exit_ccw_straight" -> 42
            "roundabout_enter_and_exit_ccw_sharp_left" -> 43
            "roundabout_enter_and_exit_ccw_normal_left" -> 44
            "roundabout_enter_and_exit_ccw_slight_left" -> 45
            "roundabout_enter_and_exit_ccw_u_turn" -> 46
            "continue" -> 47
            "ferry_boat" -> 48
            "ferry_train" -> 49
            "arrive", "arrive left", "arrive right" -> 50
            "destination_straight" -> 51
            "destination_left" -> 52
            "destination_right" -> 53
            else -> 0
        }
    }

    fun getShape(shape: String?): Int {
        return when (shape) {
            "", "enter roundabout", "arrive" -> 0
            "continue" -> 1
            "bear left" -> 2
            "bear right" -> 3
            "turn left" -> 4
            "turn right" -> 5
            "sharp left" -> 6
            "sharp right" -> 7
            "u-turn" -> 8
            "u_turn_right" -> 9
            "u_turn_left" -> 10
            else -> -1
        }
    }

    fun convertToJsonData(data: Navigation): String {
        val jsonObject = JSONObject()
        val navObject = JSONObject()
        try {
            jsonObject.put(FAutoShareDataManager.STEP_DISTANCE, data.getStepDistance())
            jsonObject.put(FAutoShareDataManager.NAV_TYPE, data.getType().toString())
            jsonObject.put(FAutoShareDataManager.STEP_UNIT, data.getStepUnit().toString())
            jsonObject.put(FAutoShareDataManager.STEP_ROAD, data.getStepRoad())
        } catch (e: JSONException) {
            Log.i("Utils", "convertToJsonData - exception : $e")
        }

        navObject.put(FAutoShareDataManager.NAV_OBJECT, jsonObject)
        return navObject.toString()
    }
}
