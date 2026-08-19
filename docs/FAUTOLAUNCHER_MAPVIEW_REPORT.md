# FAutoLauncher Mechanism & Live MapView Widget — Report

**Scope:** `launcher/` module (`FAutoLauncher`, package `com.ivi.launcher`) plus the `application/`-side
pieces it depends on for navigation data. Based on a fresh read of the source tree as it currently
stands on disk (post-rollback), not on git history.

---

## 1. Current State Summary

The live MapView widget feature (a Mapbox `MapView` embedded into the launcher's navigation home
card via `SurfaceControlViewHost`) **does not currently exist in either module**. It was implemented,
iterated on, and has since been rolled back:

| Piece | State |
|---|---|
| `application/java/.../service/MapWidgetSurfaceService.kt` | **Deleted** |
| `application/aidl/.../MapWidgetSurfaceInterface.aidl` | **Deleted** |
| `application/AndroidManifest.xml` service declaration | **Removed** |
| `NavigationManager.kt` widget-facing StateFlows (`widgetCamera`, `widgetRoutes`, `widgetLocationMatcherResult`, `widgetRouteProgress`) | **Removed** |
| `NaviFragment.kt` code that fed those StateFlows | **Removed** |
| `NavigationService.kt` widget-mirroring observers | **Removed** |
| `launcher/src/.../HomeCardViewHolder.java` SurfaceView wiring | **Removed** |
| `launcher/src/.../IviLauncher.java` widget cleanup call | **Removed** |
| `launcher/src/.../HomeCardMapSurfaceController.java`, `NavMapSurfaceCoordinator.java` | **Still present, but orphaned** — referenced by nothing else in the codebase |
| `launcher/src/aidl/.../MapWidgetSurfaceInterface.aidl` (launcher's copy) | **Still present** — inconsistent with the deleted `application/` copy |

The launcher's navigation home card currently only shows **text-based turn-by-turn data** (no map,
no puck) — the same mechanism it used before the MapView effort began.

---

## 2. FAutoLauncher Mechanism (General)

### 2.1 Entry point and carousel shell — `IviLauncher.java`

`IviLauncher` is the launcher's home `Activity` (`android.intent.category.HOME`, `LAUNCH_SINGLE_TASK`).
On `onCreate()` it:

1. Forces an opaque, transparent-system-bar window.
2. Builds the static default card list (`HomeCardDataRepository.createDefaultCards()`).
3. Sets up a horizontal `RecyclerView` (`homeCarousel`) with a plain `LinearLayoutManager`, no
   `ItemAnimator` (`setItemAnimator(null)`), and a fixed item gap decoration.
4. Wires `HomeCardDataRepository` (see §2.3) and pushes its data into the adapter.

The carousel is an **infinite loop**: `HomeCarouselAdapter.getItemCount()` returns
`Integer.MAX_VALUE`, and every real adapter position maps to a "real" card index via
`adapterPosition % items.size()`. The initial/centered position is the middle of that huge range,
offset to land on `INITIAL_REAL_FOCUS_INDEX` (Navigation, index 1). All the touch/scroll/fling
handling in `IviLauncher` exists to support this loop plus a "one card at a time is enlarged
('focused')" UX:

- `onInterceptTouchEvent`/`OnFling`/`OnScroll` listeners cancel any pending focus/center-scroll
  animation the moment the user starts a new gesture, and collapse the currently-focused card
  (`HomeCarouselAdapter.clearFocusOnUserDrag`) as soon as dragging starts.
- On scroll-idle, `scheduleFocusFromCurrentCenterIfIdle()` finds whichever child is nearest the
  recycler's horizontal center and, after a short delay, calls
  `HomeCarouselAdapter.setFocusedPosition(position, animate=true)` to grow it.
- `beginFocusTransitionAnimator()`/`finishFocusTransitionAnimator()` set/clear
  `isFocusTransitionLocked`, which the touch listener uses to swallow all input while a focus-grow
  animation is in flight (this lock is **not** engaged for the collapse/drag path — see §4.2).
- After a focus-grow animation completes, `onFocusAnimationCompleted()` schedules one more smooth
  `scrollBy` pass to perfectly re-center the now-larger card.

### 2.2 Adapter and ViewHolder — `HomeCarouselAdapter.java` / `HomeCardViewHolder.java`

`HomeCarouselAdapter` holds the card list and a single `focusedPosition`. It does **not** override
`getItemViewType()` (all 5 card types share one view type) and does **not** call
`setHasStableIds(true)`. `onBindViewHolder` computes `realPosition = adapterPosition % items.size()`
and calls `HomeCardViewHolder.bind(item, adapterPosition, focused, animate)` — `animate` is `true`
only when the bind came from a focus-change payload (`notifyItemChanged(pos, PAYLOAD_FOCUS_CHANGED)`);
a full `notifyDataSetChanged()` always binds with `animate=false`.

`HomeCardViewHolder` renders each card as two overlaid layers inside one `itemView`:

- **compact** (`compactLayer`/`compactSlot`) — the small, unfocused card.
- **focus** (`focusLayer`/`focusSlot`) — the enlarged card, shown when this position is the
  carousel's `focusedPosition`.

`ensureCardLayout(type)` inflates the type-specific compact/focus layouts
(`card_<type>_compact.xml` / `card_<type>_focus.xml`) into those slots **whenever the card's type
changes** (tracked via a `currentType` field on the ViewHolder), and is a no-op otherwise.
`applyFocusState(focused)` / `animateFocusState(focused)` then toggle visibility, alpha, and a
width/scale animation between the two layers — `animateFocusState` runs a `ValueAnimator` that grows
`cardRoot`'s width from compact (400dp) to focus (613dp) over ~1s, driving `IviLauncher.scrollCarouselBy()`
each frame to keep the growing card centered.

### 2.3 Card data pipeline — `HomeCardDataRepository` / `HomeMediaSessionProvider` / `HomeNaviDataProvider`

`HomeCardDataRepository` is the single source of truth for the 5 `HomeCardItem`s
(`Phone`, `Navigation`, `CarInfo`, `Music`, `Video`). It owns:

- `HomeMediaSessionProvider` — listens to `MediaSessionManager` for the configured music/video
  package's active `MediaController`, updates the Music/Video cards' title/artwork/playback state.
- `HomeNaviDataProvider` — see §3.

Any change from either source calls `notifyChanged()`, which snapshots the card list and delivers it
to `IviLauncher`'s registered listener. That listener updates `cardItems` and calls
`HomeCarouselAdapter.setItems(cards)` → **`notifyDataSetChanged()`** — i.e. **every** media-session
tick or navigation-data push (roughly once per second while a route is active) triggers a full
adapter rebind pass, not a targeted `notifyItemChanged`. This is significant for §4.1.

---

## 3. Navigation Home Card — Current (Text-Only) Mechanism

```
application/NavigationService.kt (routeProgressObserver, always running in background)
        │  Utils.updateNavigation(...) → builds a Navigation object
        │  publishTurnByTurnToLauncher() → JSON envelope, channel="turn-by-turn"
        ▼
application/LauncherTurnByTurnBus (in-process SharedFlow)
        ▼
application/NaviAidlService.kt  (binder service, exported)
        │  collects LauncherTurnByTurnBus.updates → broadcastNaviData() to all registered INaviListener
        ▼  (cross-process AIDL: NaviAidlInterface / INaviListener, duplicated in launcher/src/aidl)
launcher/HomeNaviDataProvider.java
        │  binds NaviAidlService, registers an INaviListener
        │  onNaviDataReceived(json) → Gson-parses into launcher's own Navigation model → NaviInfo
        ▼
launcher/HomeCardDataRepository.onNaviDataChanged(NaviInfo)
        │  updates the TYPE_NAVIGATION HomeCardItem's naviXxx text fields
        ▼
IviLauncher → HomeCarouselAdapter.setItems() → notifyDataSetChanged()
        ▼
HomeCardViewHolder.bindNaviCard() → text views only (turn icon, distance, road, destination, ETA)
```

Two independent AIDL contracts are involved here, each **hand-duplicated verbatim** between
`application/aidl/` and `launcher/src/aidl/` (no shared source): `NaviAidlInterface`/`INaviListener`
(this text channel) — the same duplication pattern that `MapWidgetSurfaceInterface` used before it
was deleted (§4.4).

There is no camera, puck, or route line in this path today — `HomeCardItem` currently carries only
turn-type/distance/road/destination/ETA text fields (no `percentTraveled`, no map-related fields).

---

## 4. Blocking Issues for a Live MapView on the Home Widget

### 4.1 Infinite-loop carousel with no stable ViewHolder identity

`HomeCarouselAdapter` shares one view type across all 5 card types and never calls
`setHasStableIds(true)`. Combined with `notifyDataSetChanged()` firing on every navigation-data push
(§2.3, roughly once per second while a route is active), RecyclerView has **no reliable way to know
"this ViewHolder instance is the Navigation card" across rebinds** — it can reassign the ViewHolder
currently showing the focused nav card to a different card type for one bind pass, and back to nav on
the very next, purely as an artifact of how it reconciles a full-dataset-changed notification against
an unbounded, ID-less, looping item count.

**Consequence for a live map:** any implementation that ties an expensive resource (a live Mapbox
`MapView`/renderer session) to a `SurfaceView` inflated by `ensureCardLayout()` will see that
`SurfaceView` instance change on this same cadence, unless something is done to counteract it. Two
approaches were tried during the prior implementation:

- Releasing and rebuilding the whole session on every such reassignment — correct but visibly
  "flashes black" once per second while navigating.
- Caching and reusing the same inflated View/`SurfaceView` across a type switch-away-and-back inside
  `HomeCardViewHolder` (without touching the adapter) — avoids the rebuild, but see §4.3 for a
  side effect this introduces.

Root-causing this properly (stable IDs, or restructuring the carousel to not be an unbounded loop
over a single shared view type) was explicitly deferred during the prior attempt because it touches
`HomeCarouselAdapter.java`, which was treated as off-limits for that iteration.

### 4.2 Navigation app's own data only flows while its Activity is foregrounded

In the (now-removed) implementation, `NavigationManager`'s widget-facing state
(`widgetCamera`/`widgetRoutes`/`widgetLocationMatcherResult`/`widgetRouteProgress`) was written
**exclusively by `NaviFragment`'s own observers**, which Mapbox's `requireMapboxNavigation(onResumedObserver = ...)`
registers only while the Fragment is `RESUMED` and unregisters the moment it isn't. The app's
separate always-alive `NavigationService` runs its own `RouteProgressObserver` continuously in the
background, but it only ever fed the legacy text channel (§3) — never the widget's camera/route/puck
state.

**Consequence:** the moment the user leaves the navigation app to look at the launcher — exactly the
scenario the widget exists for — its data source freezes at whatever was last rendered. The widget
would show a static frame, not a live view of an ongoing route simulation. Fixing this requires
duplicating (or relocating) location/route observation into the always-alive service, which is
itself extra surface area for the two observers (Fragment's and Service's) to coexist correctly.

### 4.3 `SurfaceView` re-embedding semantics

A `SurfaceView`'s embedded content (delivered via `SurfaceControlViewHost.SurfacePackage` and
attached with `SurfaceView.setChildSurfacePackage()`) stops rendering once that `SurfaceView` is
genuinely removed from its window and later re-added — even if the live session on the server side
was never torn down. Any strategy that reuses/caches a `SurfaceView` across RecyclerView churn (§4.1)
must also re-apply the last known `SurfacePackage` on every such reattachment, or the widget goes
dark despite the underlying `MapView` still running. This is a non-obvious platform behavior that is
easy to miss when a "keep the session warm" caching strategy is first introduced.

### 4.4 Cross-process AIDL duplication with no shared source of truth

`MapWidgetSurfaceInterface.aidl` (like `NaviAidlInterface`/`INaviListener`) existed as byte-for-byte
duplicate copies in `application/aidl/` and `launcher/src/aidl/`. Nothing enforces the two copies
staying in sync; a signature drift between them fails silently at the Binder-transaction level at
runtime, not at compile time. As of this report, the `launcher/` copy still exists while the
`application/` copy (and its implementation) has been deleted — the two sides of this contract are
already inconsistent.

### 4.5 The server implementation's `requestMapSurface()` was unconditionally destructive

The deleted `MapWidgetSurfaceService.requestMapSurface()` always called `release(clientToken)` (fully
tearing down the `MapView`, EGL context, and route-line/maneuver state) before building a new session
— including for a request that was really just "the same client, same SurfaceView, re-requesting
after a layout change." There was no cheaper "resize" or "reparent an existing session" path, and no
pause/resume capability — a session was either fully alive (continuously rendering, even while the
card is compact/hidden) or fully torn down. Any fix for §4.1's churn that wants to avoid both
"visible rebuild" and "wasted background rendering" needs a genuinely new capability here (e.g. a
pause/resume AIDL method), not just client-side changes.

### 4.6 Visual parity with the in-app map requires deliberate, easy-to-miss configuration

`NaviFragment`'s own map applies several **non-default** Mapbox configuration calls that a
from-scratch widget `MapView` won't get "for free": custom puck top/bearing/shadow images
(`ImageHolder.from(...)`) instead of the SDK default puck (which renders visibly smaller),
`showAccuracyRing = true`, and an `OnIndicatorPositionChangedListener` that calls
`MapboxRouteLineApi.updateTraveledRouteLine()` on every position update to produce the "vanishing"
(already-traveled) route line effect — without that listener, the route line renders in full and
never trims behind the puck. Reproducing the in-app look requires locating and copying each of these
explicitly; there's no shared configuration object between `NaviFragment` and a widget `MapView`.

### 4.7 API 32+ requirement

`SurfaceControlViewHost` (the mechanism for embedding a remote, live `MapView` into the launcher's
`SurfaceView`) requires **API 32+**. The prior implementation's client-side code already guards on
`Build.VERSION.SDK_INT < 32` and no-ops below that, but this means the feature is unavailable by
construction on any lower-API target — a fallback (the existing Bitmap-snapshot path via
`HomeNaviDataProvider`, referenced in `HomeCardMapSurfaceController`'s class doc as "left untouched
and stays visible underneath as a fallback") would need to actually exist and be wired up for those
targets, since no equivalent snapshot mechanism is present in the current source.

---

## 5. What Still Exists and Could Be Reused

- `launcher/src/com/ivi/launcher/model/home/HomeCardMapSurfaceController.java` and
  `NavMapSurfaceCoordinator.java` are fully-formed, self-contained client-side controllers (bind a
  `SurfaceView`, request/cache a `SurfacePackage`, debounce attach/release) that don't depend on
  anything else in the launcher module. They compile against `MapWidgetSurfaceInterface` and would
  work again as soon as (a) a server implementation exists on the `application/` side and (b)
  something in `HomeCardViewHolder`/`IviLauncher` calls them again.
- `launcher/src/aidl/com/ivi/car/navigation/MapWidgetSurfaceInterface.aidl` already defines the
  contract shape (`requestMapSurface(hostToken, displayId, widthPx, heightPx, clientToken): Bundle`,
  `releaseMapSurface(clientToken)`) that a rebuilt server implementation would need to match exactly
  in both AIDL copies.
- The general carousel/focus mechanism (§2.1–2.2) and the text-based TBT pipeline (§3) are both
  intact and unaffected by any of the above — a live map is an additive layer on top of, not a
  replacement for, either.

---

## 6. Suggested Path Forward (if resuming this effort)

1. Decide on §4.1's fix at the architecture level first (stable IDs vs. some other identity
   mechanism) rather than working around it client-side again — most of the other issues compound on
   top of "the SurfaceView instance can't be trusted to stay put."
2. Decide on §4.2's data-freshness design before writing any widget-rendering code — a widget that
   only shows a live view while the user is inside the app it's meant to replace has limited value.
3. Rebuild the server side (`MapWidgetSurfaceService.kt` + AIDL + manifest entry) with §4.5's
   pause/resume capability designed in from the start, rather than retrofitted later.
4. Treat §4.6 as a checklist to apply once the above are stable, not something to chase reactively
   per visual bug report.
