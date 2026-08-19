# Navigation Plugin — How It Works

## 1. Purpose

This module is an **FAuto Car platform plugin** that lets an **AI Agent** (running inside the FAuto Launcher/system process) send navigation commands (set a route, search nearby POIs, select a suggestion, go home, send turn-by-turn data) and receive events (results, errors, state changes, route changes) from a separate third‑party **Navigation App** (`com.ivi.car.navigation`), without the AI Agent ever talking to that app directly.

It sits as a **bridge/adapter** between two worlds:

- **FAuto platform side** — a stable, versioned AIDL contract (`fauto.car.navigation.*`) that app/AI-agent developers code against.
- **Navigation App side** — a separate AIDL contract (`com.ivi.car.navigation.*`) implemented by the actual navigation app, which may change independently.

```
 AI Agent / Client App                FAuto System Service                Navigation App
┌───────────────────────┐   AIDL    ┌──────────────────────────┐  AIDL   ┌───────────────────┐
│FAutoCarNavigationManager│◄────────►│ FAutoCarNavigationService │◄───────►│ NaviAidlInterface  │
│   (lib/, client API)    │IFAuto... │  (service/, system server) │Navi...  │ (3rd-party app)    │
└───────────────────────┘           └──────────────────────────┘         └───────────────────┘
```

## 2. Module layout

| Module | Role |
|---|---|
| `lib/` | Client-facing SDK. Contains the public `FAutoCarNavigationManager` Java API and the two AIDL contracts (`IFAutoCarNavigation`, `IFautoCarNavigationEventListener`) that define the boundary between client apps and the system service. Built as a shared library (`Android.bp`, `lib.iml`). |
| `service/` | The actual system-service-side implementation, `FAutoCarNavigationService`, which implements the `IFAutoCarNavigation.Stub` AIDL interface, plus the AIDL contract used to talk to the real navigation app (`com.ivi.car.navigation.NaviAidlInterface` / `INaviListener`). Bundles Retrofit/OkHttp/Gson jars (likely used elsewhere/future networking) and a prebuilt `navigation-lib.jar`. |

## 3. The two AIDL boundaries

### 3.1 `lib/src/fauto/car/navigation/IFAutoCarNavigation.aidl` (Client ↔ System Service)

This is the contract the AI Agent/manager uses to talk to the system service:

```
oneway registerFAutoCarNavigationEventListener(listener)
oneway unregisterFAutoCarNavigationEventListener(listener)
oneway sendDataTurnByTurn(String data)
oneway getSearchNearbyCategory(String category, int limit, int sortedBy)
int    setRoute(String destination)
int    selectSuggestion(String suggestionId)
String getNavigationState()
int    startNavigatingHome()
```

- `oneway` methods are **fire-and-forget** (async, queued, no blocking on caller thread) — used for streaming data and event registration.
- Non-`oneway` methods (`setRoute`, `selectSuggestion`, `startNavigatingHome`, `getNavigationState`) are **synchronous/blocking calls** that return an immediate integer result code or JSON string.

### 3.2 `IFautoCarNavigationEventListener.aidl` (System Service → Client, callback channel)

Push channel the service uses to notify the client asynchronously:

```
onResult(String data)
onError(String data)
onSearchNearByCategory(String data)
onNavigationStateChanged(String data)
onRouteChanged(String data)
```

### 3.3 `service/src/com/ivi/car/navigation/NaviAidlInterface.aidl` (System Service ↔ real Navigation App)

The service itself acts as a **client** of the actual navigation app through a second, independent AIDL contract:

```
registerListener(INaviListener listener)
unregisterListener(INaviListener listener)
sendNaviData(String data)
int    setRoute(String destination)
oneway searchNearBy(int category, int limit, int sortBy)
int    selectSuggestion(String suggestionId)
String getNavigationState()
int    startNavigatingHome()
```

With `INaviListener` providing push callbacks from the app back to the service:
```
onNaviDataReceived(String data)
onNavigationStateChanged(String stateJson)
onCommandResult(String data)
onCommandError(String data)
onRouteChanged(String data)
onSearchNearbyResult(String data)
```

This separation means the FAuto-facing contract (`IFAutoCarNavigation`) is **decoupled** from whatever the real navigation app exposes (`NaviAidlInterface`) — the service translates/maps between the two.

## 4. Client side: `FAutoCarNavigationManager` (in `lib/`)

This is the public API surface an app (or the AI Agent) instantiates and calls. Key responsibilities:

- **Lifecycle / binding**: implements `FAutoCarManagerBase.registerService(IBinder)` / `unregisterService()`, which is invoked by the platform's manager-binding framework once the `FAutoCarNavigationService` binder is available. On `registerService`, it wraps the binder with `IFAutoCarNavigation.Stub.asInterface(...)` and re-registers its internal event-listener bridge with the service if any client listeners are pending.
- **Listener management**: apps register a `FAutoCarNavigationEventListener` (a local Java interface with `onResult/onError/onSearchNearByCategory/onNavigationStateChanged/onRouteChanged`). The manager keeps a `HashSet` of these listeners and lazily registers/unregisters a single `IFautoCarNavigationEventListener.Stub` (`NavigationEventListenerToService`) with the remote service — only the first registration triggers a binder call, and only the last removal un-registers it. This fan-out pattern avoids redundant IPC.
- **Command APIs** (all guarded with `@RequiresPermission`):
  - `sendDataTurnByTurn(data)` — oneway, streams raw turn-by-turn payloads to the app.
  - `getSearchNearbyCategory(category, limit, sortedBy)` — oneway, asks for nearby POIs (hotel/hospital/restaurant/gas_station/convenience_store) sorted by distance or rating; result arrives asynchronously via `onSearchNearByCategory`.
  - `setRoute(destination)` — synchronous, returns a `NavigationResultCode` int.
  - `selectSuggestion(suggestionId)` — synchronous, picks one of the previously suggested destinations.
  - `getNavigationState()` — synchronous, returns a JSON string describing current nav state.
  - `startNavigatingHome()` — synchronous "go home" shortcut.
- **Input validation & local error dispatch**: every method validates its arguments (non-null/non-empty, valid enum) and, if the service is unavailable or arguments are invalid, synthesizes a local error JSON and dispatches it through `onError` **without even calling the service** — so failures are reported through the same event channel as remote failures, giving callers one uniform error path.
- **Threading**: uses a `Handler` (caller-supplied or main-looper default) to post all listener callbacks — ensures listener code always runs on a predictable thread even though the AIDL callback (`NavigationEventListenerToService`) arrives on a Binder thread.
- **Constants exposed to callers**: result codes (`RESULT_OK`, `ERROR_UNAVAILABLE`, `ERROR_VALUE_INVALID`, `ERROR_REMOTE_EXCEPTION`, `ERROR_OPERATION_FAILED`), navigation states (`IDLE`, `SHOWING_SUGGESTIONS`, `ROUTE_CALCULATING`, `ROUTE_SET`, `SIMULATING_DRIVE`, `UNAVAILABLE`), POI categories, and sort modes — all with `@IntDef` annotations for compile-time safety.
- Note: demo-mode APIs (NAV-005/NAV-006) are intentionally commented out / out of scope — the AI Agent is not meant to control navigation demo mode; those events are delivered through a different channel (`AiSettingEventListener`).

## 5. Service side: `FAutoCarNavigationService` (in `service/`)

This class runs inside the FAuto system server process and is the real implementation of `IFAutoCarNavigation.Stub`. It has two jobs: (a) serve the FAuto-facing AIDL contract, and (b) manage a connection to the actual navigation app and translate between the two protocols.

### 5.1 Connecting to the real navigation app

- On `init()`, it calls `bindNavigationApp()`, which binds to `com.ivi.car.navigation` / `NaviAidlService` via an explicit `Intent` + `ComponentName` and `Context.BIND_AUTO_CREATE`.
- A `ServiceConnection` (`mNavigationAppConnection`) receives the binder in `onServiceConnected`, wraps it via `NaviAidlInterface.Stub.asInterface(...)`, registers its own `INaviListener` callback (`mNavigationAppListener`) with the app, and immediately pulls the current nav state.
- If the app disconnects (`onServiceDisconnected`) or a `RemoteException` occurs, the cached service reference is cleared and an "UNAVAILABLE" state is published to all FAuto clients — so consumers always know when the underlying nav app is gone.
- `release()` unregisters the listener, unbinds, and shuts down the background thread — called on service teardown.

### 5.2 Threading model

- A dedicated `HandlerThread` (`FAUTO-NAVIGATION-SERVICE-EVENT`) + `ServiceHandler` processes all incoming AIDL calls off the Binder thread pool, keeping binder threads free.
- `oneway` calls (`sendDataTurnByTurn`, `getSearchNearbyCategory`) are simply posted as messages (`MSG_ON_SEND_DATA_TURN_BY_TURN`, `MSG_ON_GET_SEARCH_NEAR_BY`) and processed asynchronously — the caller doesn't wait. `getSearchNearbyCategory`'s app-facing call (`NaviAidlInterface.searchNearBy`) is itself `oneway` too, so the worker thread returns immediately instead of blocking on the app's response; the result arrives later via `INaviListener.onSearchNearbyResult`.
- Synchronous calls (`setRoute`, `selectSuggestion`, `startNavigatingHome`) use a **`CommandRequest`** object (own file, `CommandRequest.java`): a small state machine (`PENDING → RUNNING → COMPLETED/CANCELLED`) built on a `CountDownLatch`. The queue/timeout/interrupt dance itself lives in **`BlockingCommandGateway`** (own file), which `FAutoCarNavigationService.dispatchCommand()` delegates to.
  - `dispatchCommand()` posts the request to the handler thread and blocks the calling (Binder) thread on `request.await(5000ms)`.
  - If it times out **before** execution started, the request is cancelled and an `ERROR_OPERATION_FAILED` is returned immediately (without waiting further).
  - If it was already `RUNNING` when the timeout/interrupt hits, the caller keeps waiting for the real result instead of returning a false timeout — avoiding duplicate/racy execution.
  - This design gives synchronous semantics to the client while still executing all app-facing logic on a single serialized worker thread.

### 5.3 Command execution & mapping to the app

For each command, the service:
1. Validates arguments (non-empty strings, positive limit, valid enum) and returns `ERROR_VALUE_INVALID` early if invalid.
2. Fetches the current `NaviAidlInterface` binder; if null, triggers a re-bind attempt (`bindNavigationApp()`) and returns `ERROR_UNAVAILABLE`.
3. Calls the corresponding app method (`setRoute`, `selectSuggestion`, `startNavigatingHome`, `sendNaviData`, `searchNearBy`).
4. Maps the app's raw integer result codes to FAuto's own `NavigationResultCode` via `mapAppResultCode()` (0→OK, -1→VALUE_INVALID, -2/-4→UNAVAILABLE, other→OPERATION_FAILED).
5. On `RemoteException`, clears the cached app connection (forcing rebind next time) and reports `ERROR_REMOTE_EXCEPTION`.

`getSearchNearbyCategory` additionally maps human-readable category strings (`hotel`, `hospital`, `restaurant`, `gas_station`, `convenience_store`) and sort modes (distance/rating) to the app's internal integer codes (`mapCategoryToAppCode`, `mapSortToAppCode`), fires the now-`oneway` `NaviAidlInterface.searchNearBy(...)`, and returns `RESULT_OK` immediately. The app's JSON candidate list arrives later via `INaviListener.onSearchNearbyResult`, is normalized (`buildSearchNearbyResult`) using the cached request parameters (`mLastSearchCategory`/`mLastSearchLimit`/`mLastSearchSortedBy`), and forwarded via `onSearchNearByCategory`.

### 5.4 Event/state translation from the app

The app-facing `INaviListener` now has a dedicated method per event type, so the service no longer has to sniff a `channel` field to figure out what a push means:

- `INaviListener.onNaviDataReceived(data)` → `handleNavigationAppData()`: only used for the pure pass-through turn-by-turn stream (consumed elsewhere, e.g. the Launcher) and any other opaque payload (e.g. an echoed `sendDataTurnByTurn` input), forwarded via `handleResult(data)`.
- `INaviListener.onCommandResult(data)` / `onCommandError(data)` → `handleAppCommandEvent(data, isError)`: parses the `{api, resultCode, message, destination?}` envelope the app already builds for `NavigationCommandEvent`, maps the app result code via `mapAppResultCode()`, and dispatches `onResult`/`onError` to FAuto clients.
- `INaviListener.onNavigationStateChanged(stateJson)` → `publishNavigationAppState()`: parses the app's state (`status` + optional `destination`), maps it to FAuto's `NavigationState` enum (`mapAppState`), updates cached fields (`mLastDestination`, `mLastSuggestionId`, last search params), builds a rich JSON snapshot, and calls `onNavigationStateChanged` for all registered clients.
- `INaviListener.onRouteChanged(data)` → `publishAppRouteChanged()`: the app itself now detects the `ROUTE_SET` transition and pushes it directly, so the service no longer tracks its own "did we already forward this route" flag — it just relays the destination into `buildRouteDataJson(...)` and fires `onRouteChanged`.
- `INaviListener.onSearchNearbyResult(data)` → `forwardSearchNearbyResult(...)` (same normalization/forwarding logic as before, now triggered by a push instead of a synchronous return value).
- `getNavigationState()` (synchronous query) uses a **separate, side-effect-free** translation path (`translateNavigationAppState`) so simply *polling* state never triggers listener notifications — only real app-pushed state changes do.

### 5.5 Fan-out to multiple clients

`BinderInterfaceContainer<IFautoCarNavigationEventListener>` tracks all registered client listeners (keyed by binder identity, supporting multiple simultaneous client apps/agents). `handleResult/handleError/handleSearchNearByDataToClient/handleNavigationStateChanged/handleRouteChanged` iterate this container and invoke the corresponding callback on every registered listener. New registrants are immediately replayed the last known state via `notifyCurrentNavigationDataToClient()`.

### 5.6 JSON building

Outgoing payloads are built with `org.json.JSONObject` throughout. The small envelope builders (`buildNavigationStateJson`, `buildUnavailableStateJson`, `buildRouteDataJson`, `buildErrorJson`, `buildAppErrorJson`) delegate to a dedicated **`PluginJson`** helper class; `buildSearchNearbyResult` builds its `JSONObject`/`JSONArray` directly. There's no manual string-concatenation/escaping (`safeJson`) left in this service.

## 6. End-to-end flow examples

### Example A — AI Agent asks to navigate somewhere
1. Client calls `FAutoCarNavigationManager.setRoute("123 Main St")`.
2. Manager validates input, calls `mService.setRoute(destination)` over Binder (blocking).
3. `FAutoCarNavigationService.setRoute()` validates again, wraps in a `CommandRequest`, posts to its worker thread, and blocks the Binder thread up to 5s.
4. Worker thread calls `NaviAidlInterface.setRoute(destination)` on the real navigation app.
5. App returns an int result (or later pushes a completion via `INaviListener.onCommandResult`/`onCommandError`).
6. Result code is mapped and returned synchronously to the client; any async completion event is also broadcast via `onResult`/`onError` to all registered listeners, and a state/route change may follow through `onNavigationStateChanged`/`onRouteChanged` — the latter pushed directly by the app the instant it enters `ROUTE_SET`.

### Example B — Search nearby restaurants
1. Client calls `getSearchNearbyCategory("restaurant", 5, SORT_BY_DISTANCE)` (oneway/async).
2. Service posts `MSG_ON_GET_SEARCH_NEAR_BY`; worker thread maps category/sort to app codes, fires the `oneway NaviAidlInterface.searchNearBy(3, 5, 1)`, and returns immediately — the worker thread is never blocked waiting on the app.
3. The app resolves the search asynchronously and pushes the JSON candidate list via `INaviListener.onSearchNearbyResult`; the service normalizes it (using the cached request parameters) and calls `onSearchNearByCategory` on all registered client listeners.
4. Manager receives the callback on its Binder-thread stub, posts to its `Handler`, and finally invokes the app-supplied `FAutoCarNavigationEventListener.onSearchNearByCategory(data)` on the target thread (main thread by default).

### Example C — Navigation app disconnects unexpectedly
1. `onServiceDisconnected` fires → `clearNavigationAppService()` nulls the cached binder and calls `publishUnavailableState(...)`.
2. This synthesizes an `UNAVAILABLE` state JSON and broadcasts `onNavigationStateChanged` to every client.
3. Any subsequent command call sees `mService == null` (client side) or `navigationApp == null` (service side), triggers a rebind attempt, and immediately returns `ERROR_UNAVAILABLE`/dispatches a local error — no client hangs waiting on a dead connection.

## 7. Permissions & manifest

- `service/AndroidManifest.xml` declares two signature|privileged permissions:
  - `fauto.car.permission.NAVIGATION` — required to register event listeners / query state (read-only-ish access).
  - `fauto.car.permission.CONTROL_NAVIGATION` — required to send commands that control navigation (`setRoute`, `selectSuggestion`, `startNavigatingHome`, `sendDataTurnByTurn`, `getSearchNearbyCategory`).
- It also requests numerous system-level permissions (location, internet, settings, storage, driving state, `BIND_NAVI_AIDL`) needed to bind the navigation app and support broader platform integration.
- `lib/AndroidManifest.xml` is minimal — it's just a library manifest for `fauto.car.navigation` package declaration; the actual permission enforcement happens at the service side plus `@RequiresPermission` compile-time hints on the client API.

## 8. Build artifacts

- `service/libs/` bundles `navigation-lib.jar` (compiled output of the `lib/` module, likely dropped in for prebuilt linking) plus Retrofit/OkHttp/Gson/okio jars — suggesting networking capability is available to the service (e.g., for future cloud-based features) even though the current `FAutoCarNavigationService` code shown doesn't directly use Retrofit.
- `Android.bp`/`Android.mk` files integrate both modules into the AOSP-style build system.

## 9. Summary

This plugin is a clean **adapter/facade** layer:
- Exposes a small, permission-gated, versioned AIDL API (`IFAutoCarNavigation`) for AI Agents/apps to drive navigation (set route, search nearby, select suggestion, go home, stream turn-by-turn data) and to receive results/errors/state/route-change events.
- Internally, the system service binds to the real navigation app over its own AIDL (`NaviAidlInterface`), forwards commands, and translates app-specific status codes/state strings into a stable, normalized JSON+enum contract for all FAuto clients.
- Handles connection lifecycle robustly (auto rebind attempts, unavailable-state broadcasting) and threading correctly (dedicated worker thread + blocking/oneway split + `CommandRequest`/`BlockingCommandGateway` latch mechanism), so multiple simultaneous client listeners get consistent state without blocking each other or the Binder thread pool. Nearby search is now fully asynchronous end-to-end (no Binder-thread or worker-thread blocking on the app's response).
