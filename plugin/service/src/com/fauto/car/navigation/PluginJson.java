package com.fauto.car.navigation;

import org.json.JSONException;
import org.json.JSONObject;

/** JSONObject-based builders for the plugin's outgoing event payloads. */
final class PluginJson {
    private PluginJson() {
    }

    static String navigationState(
            int state,
            String stateName,
            String destination,
            String suggestionId,
            String lastSearchCategory,
            int lastSearchLimit,
            int lastSearchSortedBy,
            String lastSearchSortedByName) {
        try {
            return new JSONObject()
                    .put("state", state)
                    .put("stateName", stateName)
                    .put("destination", destination)
                    .put("suggestionId", suggestionId)
                    .put("lastSearchCategory", lastSearchCategory)
                    .put("lastSearchLimit", lastSearchLimit)
                    .put("lastSearchSortedBy", lastSearchSortedBy)
                    .put("lastSearchSortedByName", lastSearchSortedByName)
                    .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to build navigation state json", error);
        }
    }

    static String unavailableState(
            int unavailableState,
            String lastSearchCategory,
            int lastSearchLimit,
            int lastSearchSortedBy,
            String lastSearchSortedByName) {
        try {
            return new JSONObject()
                    .put("state", unavailableState)
                    .put("stateName", "UNAVAILABLE")
                    .put("destination", "")
                    .put("suggestionId", "")
                    .put("lastSearchCategory", lastSearchCategory)
                    .put("lastSearchLimit", lastSearchLimit)
                    .put("lastSearchSortedBy", lastSearchSortedBy)
                    .put("lastSearchSortedByName", lastSearchSortedByName)
                    .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to build unavailable state json", error);
        }
    }

    static String routeData(String destination, String suggestionId, int state, String stateName) {
        try {
            return new JSONObject()
                    .put("api", "route")
                    .put("destination", destination)
                    .put("suggestionId", suggestionId)
                    .put("state", state)
                    .put("stateName", stateName)
                    .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to build route data json", error);
        }
    }

    static String errorJson(int code, String message, String api) {
        try {
            return new JSONObject()
                    .put("api", api)
                    .put("resultCode", code)
                    .put("message", message)
                    .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to build error json", error);
        }
    }

    static String appErrorJson(String api, int appResultCode, int mappedResultCode, String message) {
        try {
            return new JSONObject()
                    .put("api", api)
                    .put("resultCode", mappedResultCode)
                    .put("appResultCode", appResultCode)
                    .put("message", message)
                    .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to build app error json", error);
        }
    }
}
