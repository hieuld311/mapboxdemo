# Navigation App — Architecture Report

**Date:** August 10, 2026
**Package:** `com.ivi.car.navigation`
**Scope:** In-vehicle infotainment (IVI) navigation app built on the Mapbox Navigation SDK, exposing an AIDL interface for external system processes (launcher, voice assistant, cluster, etc.) to control navigation and receive live updates.

---

## Overview

This is an **in-vehicle infotainment (IVI) Navigation app** (`com.ivi.car.navigation`) built on the **Mapbox Navigation SDK**, running with `sharedUserId="android.uid.system"` (a privileged system app). It exposes an **AIDL interface** so other processes on the car head-unit (a launcher, a voice-assistant/"AI Agent" service, cluster display, etc.) can control navigation and receive updates, while internally it talks to several other vendor IPC services (FAuto Car, mock-location, IVI navigation assistant).

---

## 1. AIDL contract (the "plugin interface")

**`NaviAidlInterface.aidl`** — the command surface exposed to external clients:

- `registerListener/unregisterListener(INaviListener)` — subscribe to callbacks
- `sendNaviData(String)` — push raw turn-by-turn JSON to listeners
- `setRoute(String destination)` — route by free-text destination
- `searchNearBy(category, limit, sortBy)` — oneway POI search (hotel/hospital/restaurant/gas/convenience); result delivered asynchronously via `onSearchNearbyResult`
- `selectSuggestion(String suggestionId)` — confirm a candidate from a previous search and start routing
- `getNavigationState()` — poll full state as JSON
- `startNavigatingHome()` — route to saved Home location

**`INaviListener.aidl`** — the callback interface implemented by clients:

- `onNaviDataReceived(String data)` — turn-by-turn/live nav data push
- `onNavigationStateChanged(String stateJson)` — full state-machine updates
- `onCommandResult(String data)` / `onCommandError(String data)` — success/failure ack for `setRoute`/`selectSuggestion`/`startNavigatingHome`
- `onRouteChanged(String data)` — fired once, the instant status enters `ROUTE_SET`
- `onSearchNearbyResult(String data)` — async result for `searchNearBy`

---

## 2. Service side (`NaviAidlService`)

Implements `NaviAidlInterface.Stub` as a **bound, exported Service** (protected by custom permission `android.permission.BIND_NAVI_AIDL`, action `com.ivi.car.navigation.service.NaviAIDLService`). It:

- Keeps a `RemoteCallbackList<INaviListener>` of connected clients.
- Delegates every command (`setRoute`, `searchNearBy`, `selectSuggestion`, `startNavigatingHome`, `getNavigationState`) to the singleton **`NavigationManager`**. `searchNearBy` is fire-and-forget: the result is pushed later via `onSearchNearbyResult` instead of blocking the Binder thread.
- On `onCreate`, subscribes to `NavigationManager.state` (a `StateFlow`) and `NavigationManager.commandEvents` (a `SharedFlow`). State changes broadcast via `onNavigationStateChanged`, throttled to at least every 500ms unless a structural change occurs; `NavigationCommandEvent` SUCCESS/ERROR route to dedicated `onCommandResult`/`onCommandError`; the instant status enters `ROUTE_SET` a dedicated `onRouteChanged` fires too.
- Also relays `LauncherTurnByTurnBus` updates (in-process bridge from the foreground `NavigationService`) to AIDL listeners via `onNaviDataReceived` — this lets a car launcher receive turn-by-turn text via the same AIDL channel.

---

## 3. Core logic (`NavigationManager` — singleton "brain")

A stateless-facing object holding:

- `state: StateFlow<NavigationState>` — single source of truth (status enum: `IDLE`, `SHOWING_SUGGESTIONS`, `ROUTE_CALCULATING`, `ROUTE_SET`, `SIMULATING_DRIVE`, `UNAVAILABLE`; plus destination, suggestions, home/work, map style, demo mode, progress, message, version).
- `commandEvents: SharedFlow<NavigationCommandEvent>` — success/error acks tied back to whichever AIDL command triggered a route request (needed because routing is async against Mapbox's router).
- Repositories: `HomeRepository`, `WorkRepository` (saved places via SharedPreferences), `NavigationSearchRepository` (wraps `TripadvisorRepository` for POI/nearby/autocomplete search).
- Holds a reference to the actual `MapboxNavigation` instance (attached from the UI fragment) and the current GPS `Point` (updated from the UI's location observer).
- `setRoute`/`selectSuggestion`/`startNavigatingHome` all funnel into `requestRoute(...)`, which builds `RouteOptions`, calls `mapboxNavigation.requestRoutes(...)`, and on success calls `setNavigationRoutes` and updates state to `ROUTE_SET`. Failures set `UNAVAILABLE` + emit a `NavigationCommandEvent.ERROR` correlated by a `PendingRouteCommand` id (so a stale/replaced route request doesn't wrongly ack).
- `searchNearby`/`searchDestinationSuggestions` validate category/sort/limit, call the search repository, cache candidates by `suggestionId`, and update state to `SHOWING_SUGGESTIONS`.
- Returns `NavigationResultCode` ints (`ACCEPTED`, `INVALID_ARGUMENT`, `UNAVAILABLE`, `LOCATION_UNAVAILABLE`, `NOT_FOUND`, `HOME_NOT_CONFIGURED`, `WORK_NOT_CONFIGURED`, `INTERNAL_ERROR`) for every command — this is exactly what flows back through the AIDL Stub's `Int` returns.

---

## 4. UI layer

- **`NavigationApp`** (Hilt `Application`) calls `NavigationManager.initialize(context)` at process start.
- **`MainActivity`** hosts `NaviFragment`, copies Mapbox offline assets, hides system bars, and starts/stops the foreground `NavigationService`.
- **`NaviFragment`** is the map screen: draws the Mapbox `MapView`, route line/arrows, maneuver banner, trip progress, search box with POI category chips, Home/Work shortcuts, style picker popup. It:
  - Binds to `NaviAidlService` itself (`bindToAidlService`) to get a `NaviAidlInterface` reference just to push `sendNaviData()` — i.e. the app is both **server** (via `NaviAidlService`) and a **local client** of its own AIDL for pushing raw data out.
  - Binds to a separate **mock-location service** (`ILocationUpdate` AIDL, package `com.car.ivi.mocklocation`) to feed simulated GPS positions during route replay simulation.
  - Registers Mapbox observers (`RoutesObserver`, `LocationObserver`, `RouteProgressObserver`, `VoiceInstructionsObserver`) for its own visuals — on a new route it draws the route line, moves the camera to overview then following, and shows the maneuver/trip-progress cards.
  - Observes `NaviViewModel.navigationState` (mirrors `NavigationManager.state`) to drive UI visibility per status.
- **`NaviViewModel`** (Hilt `ViewModel`) is the bridge between UI/Activity-scoped `LiveData` and the app-wide `NavigationManager`, plus integrates with `IviNavigationEventManager` (a separate AIDL/IPC "assistance" service for voice-agent requests: `onRequestRoute`, `onSearchNearBy`, error/success/message callbacks) and `FAutoCar`/`FAutoCarClusterControlManager` (vendor IPC to push nav data to the instrument cluster). It also registers its own `RoutesObserver` directly on the shared `MapboxNavigation` instance, independent of `NaviFragment`'s Resumed-only lifecycle, so drive-replay simulation and the foreground `NavigationService` still auto-start when a route is requested while the app is backgrounded.
- **`BroadcastReceiver.kt`** rebroadcasts two custom actions (`REQUEST_ROUTE_BY_PLACE`, `REQUEST_FIND_NEARBY`) from system broadcasts into `LocalBroadcastManager`, which `NaviViewModel` listens to — another external entry point besides AIDL.

---

## 5. Companion foreground service (`NavigationService`)

A separate `Service` (not the AIDL one) that:

- Runs as a foreground service (`dataSync` type) showing a persistent notification.
- Connects to `FAutoCar`/`FAutoShareDataManager`/`FAutoCarClusterControlManager` (vendor car IPC).
- Reuses the already-created `MapboxNavigationProvider` instance (only works if the Activity/Fragment already created one) and registers its own `RouteProgressObserver` to keep pushing turn-by-turn data (`sendNaviData`, `sendNavDataToSomeIp`, `publishTurnByTurnToLauncher`) even when `MainActivity` isn't visible/running — this is what feeds `LauncherTurnByTurnBus`, which `NaviAidlService` then republishes to AIDL listeners. It self-stops when the route completes.

---

## 6. Data flow summary

```
External client (launcher/agent)
      │  bind + AIDL calls (setRoute/searchNearBy/selectSuggestion/startNavigatingHome/getNavigationState)
      ▼
NaviAidlService (Stub)  ──────────────► NavigationManager (state machine, Mapbox routing, search)
      ▲                                        │
      │ callbacks (onNavigationStateChanged/   │ StateFlow<NavigationState> / SharedFlow<CommandEvent>
      │ onCommandResult/onCommandError/         ▼
      │ onRouteChanged/onSearchNearbyResult)
      │                                  NaviFragment/NaviViewModel (UI, map, simulation)
      │                                        │
      └── LauncherTurnByTurnBus  ◄──── NavigationService (foreground, cluster/FAuto IPC, turn-by-turn while app backgrounded)
```

In short: **`NaviAidlInterface`/`INaviListener`** form the public IPC contract other system apps use to command navigation and receive live status; **`NavigationManager`** is the single stateful engine wrapping Mapbox routing/search; and the UI (`NaviFragment`/`NaviViewModel`) plus the background `NavigationService` are two parallel consumers/producers of that state — one for the on-screen map/simulation experience, the other for pushing data to the instrument cluster and external listeners even when the app isn't in the foreground.

---

## Key Files Reference

| Concern | File |
|---|---|
| AIDL command interface | `app/src/main/aidl/com/ivi/car/navigation/NaviAidlInterface.aidl` |
| AIDL callback interface | `app/src/main/aidl/com/ivi/car/navigation/INaviListener.aidl` |
| AIDL service implementation | `app/src/main/java/com/ivi/car/navigation/service/NaviAidlService.kt` |
| Core state machine / Mapbox routing | `app/src/main/java/com/ivi/car/navigation/controller/NavigationManager.kt` |
| Domain models & JSON contracts | `app/src/main/java/com/ivi/car/navigation/model/NavigationContract.kt` |
| Cluster/turn-by-turn data model | `app/src/main/java/com/ivi/car/navigation/model/Navigation.kt` |
| Application entry point | `app/src/main/java/com/ivi/car/navigation/NavigationApp.kt` |
| Main activity | `app/src/main/java/com/ivi/car/navigation/ui/MainActivity.kt` |
| Map UI fragment | `app/src/main/java/com/ivi/car/navigation/ui/NaviFragment.kt` |
| UI-facing ViewModel | `app/src/main/java/com/ivi/car/navigation/viewmodel/NaviViewModel.kt` |
| Foreground service (cluster/FAuto push) | `app/src/main/java/com/ivi/car/navigation/service/NavigationService.kt` |
| In-process bus (foreground service → AIDL) | `app/src/main/java/com/ivi/car/navigation/service/LauncherTurnByTurnBus.kt` |
| Local broadcast rebroadcaster | `app/src/main/java/com/ivi/car/navigation/broadcast/BroadcastReceiver.kt` |
| Hilt DI module | `app/src/main/java/com/ivi/car/navigation/di/NavigationModule.kt` |
| Manifest (permissions, exported services) | `app/src/main/AndroidManifest.xml` |
