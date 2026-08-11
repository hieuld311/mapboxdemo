package com.ivi.car.navigation.model

import android.car.cluster.navigation.NavigationState
import android.car.cluster.navigation.NavigationState.Lane.LaneDirection
import com.google.gson.annotations.SerializedName
import org.json.JSONObject

class Navigation {
    private var currentRoad: String? = null
    private var destination: String? = null
    private var duration = 0
    private var distance = 0.0
    private var stepRoad: String? = null
    private var stepDuration = 0
    private var stepDistance = 0.0
    private var stepUnit: NavigationState.Distance.Unit? = null
    private var distanceUnit: NavigationState.Distance.Unit? = null
    private var cue: String? = null
    private var lane: LaneDirection.Shape? = null
    private var type: NavigationState.Maneuver.Type? = null

    fun getCurrentRoad(): String? {
        return currentRoad
    }

    fun setCurrentRoad(currentRoad: String?) {
        this.currentRoad = currentRoad
    }

    fun getLane(): LaneDirection.Shape? {
        return lane
    }

    fun setLane(lane: LaneDirection.Shape?) {
        this.lane = lane
    }

    fun getDestination(): String? {
        return destination
    }

    fun setDestination(destination: String?) {
        this.destination = destination
    }

    fun getType(): NavigationState.Maneuver.Type? {
        return type
    }

    fun setType(type: NavigationState.Maneuver.Type?) {
        this.type = type
    }

    fun getDuration(): Int {
        return duration
    }

    fun setDuration(duration: Int) {
        this.duration = duration
    }

    fun getDistance(): Double {
        return distance
    }

    fun setDistance(distance: Double) {
        this.distance = distance
    }

    fun getStepDuration(): Int {
        return stepDuration
    }

    fun setStepDuration(stepDuration: Int) {
        this.stepDuration = stepDuration
    }

    fun getStepDistance(): Double {
        return stepDistance
    }

    fun setStepDistance(stepDistance: Double) {
        this.stepDistance = stepDistance
    }

    fun getCue(): String? {
        return cue
    }

    fun setCue(cue: String?) {
        this.cue = cue
    }

    fun getStepUnit(): NavigationState.Distance.Unit? {
        return stepUnit
    }

    fun setStepUnit(stepUnit: NavigationState.Distance.Unit?) {
        this.stepUnit = stepUnit
    }

    fun getStepRoad(): String? {
        return stepRoad
    }

    fun setStepRoad(stepRoad: String?) {
        this.stepRoad = stepRoad
    }

    fun getDistanceUnit(): NavigationState.Distance.Unit? {
        return distanceUnit
    }

    fun setDistanceUnit(distanceUnit: NavigationState.Distance.Unit?) {
        this.distanceUnit = distanceUnit
    }

    override fun toString(): String {
        return JSONObject()
            .put("currentRoad", currentRoad.toString())
            .put("destination", destination.toString())
            .put("duration", duration)
            .put("distance", distance)
            .put("distanceUnit", distanceUnit.toString())
            .put("stepRoad", stepRoad.toString())
            .put("stepDuration", stepDuration)
            .put("stepDistance", stepDistance)
            .put("stepUnit", stepUnit.toString())
            .put("cue", cue.toString())
            .put("lane", lane.toString())
            .put("type", type.toString())
            .toString()
    }
}

data class TurnByTurn(
    @SerializedName("maneuvererID")
    var maneuvererID: Int = 0,
    @SerializedName("distanceToTurnPoint")
    var distanceToTurnPoint: Double = 0.0,
    @SerializedName("distanceToTurnPointUnit")
    var distanceToTurnPointUnit: Int = 0,
    @SerializedName("turnSide")
    var turnSide: Int = 0,
    @SerializedName("distanceToTurnPointTxt")
    var distanceToTurnPointTxt: String? = null,
    @SerializedName("distanceToTurnPointUnitTxt")
    var distanceToTurnPointUnitTxt: String? = null,
    @SerializedName("turnPointName")
    var turnPointName: String? = null,
    var type: String? = null
)


class LaneInformation {
    var totalLane: Int = 0
    var listHiPassLane: List<Int>? = null
    var listOverPassLane: List<Int>? = null
    var listPriorityLane: List<Int>? = null
    var listTurnLeftLane: List<Int>? = null
    var listTurnRightLane: List<Int>? = null
}

data class Eta(
    @SerializedName("timeInMinutes")
    var timeInMinutes: Int = 0
)

data class Dtd(
    @SerializedName("distanceToDestination")
    var distanceToDestination: Double = 0.0,
    @SerializedName("distanceUnit")
    var distanceUnit: Int = 0
)

data class Compass(
    var angleToTheNorth: Int = 0
)

data class SignBoardDetection(
    var signBoardKind: Int = 0,
    var signBoardValue: Int = 0,
    var signBoardUnit: Int = 0
)

data class LaneChangeWarning(
    var laneChangePath: Int = 0,
    var frontLeftVehicleCollisionStatus: Int = 0,
    var frontLeftVehicleLongditudinal: Int = 0,
    var frontLeftVehicleLateral: Int = 0,
    var frontRightVehicleCollisionStatus: Int = 0,
    var frontRightVehicleLongditudinal: Int = 0,
    var frontRightVehicleLateral: Int = 0,
    var leftVehicleCollisionStatus: Int = 0,
    var leftVehicleLongditudinal: Int = 0,
    var leftVehicleLateral: Int = 0,
    var rightVehicleCollisionStatus: Int = 0,
    var rightVehicleLongditudinal: Int = 0,
    var rightVehicleLateral: Int = 0,
    var leftLineLateral: Int = 0,
    var rightLineLateral: Int = 0,
    var laneCurver: Int = 0
)

data class AssistanceInformation(
    var assistanceInformationType: Int = 0,
    var nameInfo: String? = null,
    var distanceToPlace: Int = 0,
    var assistanceUnit: Int = 0,
    var assistanceStatus: Int = 0,
    var parkingSlots: Int = 0,
    var longditudinal: Int = 0,
    var lateral: Int = 0
)