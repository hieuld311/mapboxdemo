# Agent Plugin Design vs. Implementation — Gap Analysis

**Date:** August 10, 2026 (updated same day after a refactor closing the gaps below)
**Source design doc:** `agentdesign.md`
**Verdict: The current architecture now matches the design closely.** The callback model, the nearby-search API shape, and headless simulation auto-start — the three substantive gaps originally identified in this document — have all been closed. Naming/error-code vocabulary differences between the app's internal contract and the plugin's public contract remain, but that is an intentional adapter boundary, not a gap.

---

## 1. Item-by-item comparison

### 1. Route — `int setRoute(String destination)`
- **Design:** search POI by keyword, set top match as destination, auto-start drive simulation.
- **Actual:** `NaviAidlInterface.setRoute(String)` → `NaviAidlService.Stub.setRoute()` → `NavigationManager.setRoute()`.
  - Signature matches exactly.
  - Resolves the destination via `NavigationSearchRepository.resolveDestination()`, then calls `requestRoute()` which sets the route on the shared `MapboxNavigation` instance on success.
  - **Resolved:** `NaviViewModel` now registers its own `RoutesObserver` directly on the shared `MapboxNavigation` instance, independent of `NaviFragment`'s Resumed-only lifecycle, so drive simulation and the foreground `NavigationService` still auto-start when `setRoute` is triggered while the UI is backgrounded. `NaviFragment`'s own `routesObserver` keeps its visual responsibilities (route line, camera transitions, card visibility) unchanged.
- **Callback gap — resolved:** `INaviListener` (both the app's and the plugin's private copy) now also defines `onCommandResult`, `onCommandError`, and `onRouteChanged` alongside `onNaviDataReceived`/`onNavigationStateChanged`. `NaviAidlService` routes `NavigationCommandEvent` SUCCESS/ERROR to the dedicated methods and emits `onRouteChanged` the instant status enters `ROUTE_SET`, instead of overloading `onNaviDataReceived` with a `"channel":"navigation-command"` JSON discriminator.
- **Error codes:** unchanged — design wants `ERROR_UNAVAILABLE`, `ERROR_VALUE_INVALID`, `ERROR_REMOTE_EXCEPTION`, `ERROR_OPERATION_FAILED` at the FAuto-facing boundary (already matched by `FAutoCarNavigationManager`'s constants); the app's own internal `NavigationResultCode` intentionally uses a richer, app-specific vocabulary (`ACCEPTED, INVALID_ARGUMENT, UNAVAILABLE, INVALID_STATE, LOCATION_UNAVAILABLE, NOT_FOUND, HOME_NOT_CONFIGURED, WORK_NOT_CONFIGURED, INTERNAL_ERROR`) that `FAutoCarNavigationService.mapAppResultCode()` translates down to the public set — this is an intentional adapter boundary, not a gap.

### 2. Nearby Search — `void getSearchNearbyCategory(String category, int limit, int sortedBy)`
- **Design:** asynchronous query; results delivered via `onSearchNearByCategory(String data)` callback; errors via `onError(String data)`.
- **Resolved:** the plugin-facing `getSearchNearbyCategory` was already `oneway`/async and unchanged. The gap was internal: `NaviAidlInterface.searchNearBy` used to be a synchronous `String` return, wrapped in `runBlocking(Dispatchers.IO) { withTimeout(25_000) { ... } }` on both the app's Binder thread and the plugin's serialized worker thread. It is now `oneway void searchNearBy(int category, int limit, int sortBy)`; the app resolves the search asynchronously and pushes the result via the new `INaviListener.onSearchNearbyResult(String data)`, which the plugin forwards to `onSearchNearByCategory` exactly as before. No Binder thread or worker thread blocks on the app's response anymore.
  - `category`/`sortBy` remain int codes at the app boundary and strings at the plugin boundary — this is the same intentional decoupling as the result-code vocabulary above, not a gap.

### 3. Suggestion — `int selectSuggestion(String suggestionId)`
- Matches design exactly (signature, semantics: pick a cached candidate by stable ID, start routing).
- Same callback resolution as item 1 (`onCommandResult`/`onCommandError`/`onRouteChanged`).

### 4. Navigation State — `String getNavigationState()`
- Matches exactly. `NavigationManager.getStateJson()` serializes the full `NavigationState` (status, destination, suggestions, home/work, map style, demo mode, progress, message, version).

### 7. Home Navigation — `int startNavigatingHome()`
- Matches exactly. Returns `HOME_NOT_CONFIGURED` if no saved home, otherwise routes via the same `requestRoute()` path (same auto-simulation resolution as item 1 applies).

### 8/9. Event Listener Register/Unregister
- **Design:** `registerFAutoCarNavigationEventListener` / `unregisterFAutoCarNavigationEventListener(FAutoCarNavigationEventListener listener)` — listener receiving result, error, nearby-search result, state, demo mode, and route callbacks.
- **Actual:** `registerListener` / `unregisterListener(INaviListener)` on `NaviAidlInterface`. This is the app-facing boundary, distinct from the plugin's public `IFAutoCarNavigation`/`FAutoCarNavigationEventListener` contract, which already matched the design (`onResult`/`onError`/`onSearchNearByCategory`/`onNavigationStateChanged`/`onRouteChanged`) before this refactor.
- The app-facing `INaviListener` used to be minimal (2 methods) and is now 6 methods (`onNaviDataReceived`, `onNavigationStateChanged`, `onCommandResult`, `onCommandError`, `onRouteChanged`, `onSearchNearbyResult`) — enough for the plugin to relay every design-item callback without deriving/guessing state transitions itself.
- `ivi.navigation.IviNavigationEventManager.INavigationEventListener` remains a **separate, vendor-supplied, inbound-only** voice-assistant channel used inside `NaviViewModel`; it is intentionally not part of this plugin surface and was out of scope for this refactor.

### 10. Turn-by-turn — `void sendDataTurnByTurn(String data)`
- **Actual:** `void sendNaviData(String data)` on `NaviAidlInterface` — same purpose, different method name (unchanged; a rename here would only be cosmetic).
- **Resolved:** the plugin's `sendTurnByTurn()` previously only reported failures; it was missing the success `onResult` ack the design's callback list (`onResult`/`onError`) requires. It now calls `handleResult(...)` after a successful `sendNaviData` forward.

---

## 2. Cross-cutting gaps — status after the refactor

1. ~~No dedicated per-command result/error callback.~~ **Resolved** — `onCommandResult`/`onCommandError`/`onRouteChanged` added to `INaviListener`; `handleNavigationAppData`'s `"navigation-command"` channel-sniffing and the plugin's own `mLastForwardedRouteState` edge-detection were both removed as redundant.
2. ~~Nearby search is synchronous, not async-with-callback.~~ **Resolved** — see item 2 above.
3. **Naming/vocabulary differences between the app's internal contract and the plugin's public contract remain** (`NavigationResultCode` vs. `FAutoCarNavigationManager` result codes; int category/sort codes vs. strings). This is an intentional adapter boundary decoupling the app's internal API from the FAuto-facing one, not a defect — left unchanged.
4. ~~Auto-simulation-start on route-set is UI-lifecycle-dependent.~~ **Resolved** — `NaviViewModel` now owns an always-on `RoutesObserver`/simulation trigger independent of `NaviFragment`'s Resumed state.
5. **Demo mode remains intentionally out of scope**, consistent with comments in `NaviAidlInterface.aidl` (`setNavigationDemoMode` commented out) and `NaviAidlService`/`NavigationManager` ("NAV-005/006 out of scope") — unchanged, not part of this refactor.

---

## 3. What DOES match

| Design item | Match |
|---|---|
| `setRoute(String)` signature & semantics | ✅ |
| `selectSuggestion(String)` signature & semantics | ✅ |
| `getNavigationState()` signature & semantics | ✅ |
| `startNavigatingHome()` signature & semantics | ✅ |
| `onNavigationStateChanged` callback | ✅ |
| `onResult`/`onError`/`onRouteChanged` callbacks | ✅ (resolved by this refactor) |
| `getSearchNearbyCategory` async + `onSearchNearByCategory` | ✅ (resolved by this refactor) |
| Turn-by-turn push concept (name differs) + success ack | ✅ (resolved by this refactor) |
| Auto-start drive simulation regardless of UI foreground state | ✅ (resolved by this refactor) |
| Overall command/query split (some sync, some async) | ✅ |

## 4. Refactor summary (supersedes the old "Recommendation" section)

The following were implemented to close the gaps above:
- Added `onCommandResult(String)`, `onCommandError(String)`, `onRouteChanged(String)`, `onSearchNearbyResult(String)` to both descriptor-identical copies of `INaviListener.aidl`; `NaviAidlService` and `FAutoCarNavigationService` were updated to use them directly instead of channel-sniffing a generic `onNaviDataReceived` payload.
- Converted `NaviAidlInterface.searchNearBy` to `oneway void`, removing the `runBlocking`/`withTimeout(25_000)` blocking call on both sides.
- Fixed `sendDataTurnByTurn`'s missing success ack.
- Decoupled "start simulation on route ready" and "ensure foreground service" from `NaviFragment`'s Resumed-only observer by giving `NaviViewModel` its own always-on `RoutesObserver`.
- Reduced incidental duplication as part of the same pass: shared `GeoUtils.haversineMeters`, a shared `NavigationManager.suggestionFor(...)` factory, and (plugin-side) `PluginJson` (JSONObject-based builders replacing manual string concatenation/escaping) plus `CommandRequest`/`BlockingCommandGateway` extracted out of `FAutoCarNavigationService` into their own files.

`FAutoCarNavigationEventListener` (the plugin's public listener) was never a gap — it already matched the design before this refactor; the gap was entirely in the app-facing `INaviListener` boundary the plugin talks to internally, which is now resolved.
