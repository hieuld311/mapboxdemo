/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ivi.launcher.model;

/**
 * Meta data of an app including the display name, the full package name, the icon drawable, and an
 * intent to either open the app or the media center (for media services).
 */

public class Navigation {

    private String currentRoad;
    private String destination;
    private int duration;
    private double distance;
    private String distanceUnit;
    private String stepRoad;
    private int stepDuration;
    private double stepDistance;
    private String stepUnit;
    private String cue;
    private String lane;
    private String type;
    // Overall route fraction traveled, 0.0-1.0. Same field name as the nav app's
    // com.ivi.car.navigation.model.Navigation#percentTraveled - Gson matches by field name on
    // both sides of the JSON channel (onNaviDataReceived), so the names must stay identical.
    private double percentTraveled;

    // A default constructor is required by many deserialization libraries
    public Navigation() {}

    // Getters and Setters (essential for libraries like Jackson/Gson to access the fields)
    public String getCurrentRoad() { return currentRoad; }
    public void setCurrentRoad(String currentRoad) { this.currentRoad = currentRoad; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public int getDuration() { return duration; }
    public void setDuration(int duration) { this.duration = duration; }

    public double getDistance() { return distance; }
    public void setDistance(double distance) { this.distance = distance; }

    public String getDistanceUnit() { return distanceUnit; }
    public void setDistanceUnit(String distanceUnit) { this.distanceUnit = distanceUnit; }

    public String getStepRoad() { return stepRoad; }
    public void setStepRoad(String stepRoad) { this.stepRoad = stepRoad; }

    public int getStepDuration() { return stepDuration; }
    public void setStepDuration(int stepDuration) { this.stepDuration = stepDuration; }

    public double getStepDistance() { return stepDistance; }
    public void setStepDistance(double stepDistance) { this.stepDistance = stepDistance; }

    public String getStepUnit() { return stepUnit; }
    public void setStepUnit(String stepUnit) { this.stepUnit = stepUnit; }

    public String getCue() { return cue; }
    public void setCue(String cue) { this.cue = cue; }

    public String getLane() { return lane; }
    public void setLane(String lane) { this.lane = lane; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public double getPercentTraveled() { return percentTraveled; }
    public void setPercentTraveled(double percentTraveled) { this.percentTraveled = percentTraveled; }

    // An optional toString() method for easy printing and debugging
    @Override
    public String toString() {
        return "Navigation{" +
                "currentRoad='" + currentRoad + '\'' +
                ", destination='" + destination + '\'' +
                ", duration=" + duration +
                ", distance=" + distance +
                ", distanceUnit='" + distanceUnit + '\'' +
                ", stepRoad='" + stepRoad + '\'' +
                ", stepDuration=" + stepDuration +
                ", stepDistance=" + stepDistance +
                ", stepUnit='" + stepUnit + '\'' +
                ", cue='" + cue + '\'' +
                ", lane='" + lane + '\'' +
                ", type='" + type + '\'' +
                ", percentTraveled=" + percentTraveled +
                '}';
    }
}