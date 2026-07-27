# TAKPilot2 → DJI Mobile SDK V4 — Port Summary

*A write-up for the original TAKPilot2 author, covering what we built on top of the source you
shared, what we had to change in the shared taklite core, and a few findings from your code that
are probably worth taking upstream.*

**Status:** flying and field-proven. Aircraft is a **DJI Mini 2** on an RC-N1, phone is a Pixel 8 Pro.

---

## Why a V4 port at all

Your TAKPilot2 targets DJI **MSDK V5**, which doesn't support the Mini 2 (or any of the older
consumer airframes). We needed the Mini 2 specifically, so this is a port of your app onto
**MSDK 4.18**.

That constraint drove essentially every structural difference below. V4 has a completely
different API surface — callback/listener based rather than V5's `KeyManager`/`KeyTools`
key-value model — so anything that touched the SDK had to be rewritten rather than moved. The
toolchain is also pinned much older as a result: Gradle 6.7.1 / JDK 11 / AGP 4.2.2 /
Kotlin 1.5.10, minSdk 23, MapLibre 9.6.0, Room 2.2.6.

## What ported unchanged — and this is the reason the project was feasible

**Your `com/taklite/` core is SDK-agnostic and it held up completely.** We dropped it in
essentially as-is and it worked:

- `TakManager` — TLS CoT client, enrollment, contact tracking, channel/`<marti>` scoping
- `CotBuilder` — CoT XML generation
- `TakCertEnroller` — cert enrollment over HTTPS
- `CotParser`, `TakUser`, the Data Sync / mission client
- `AppLog`

This was the single biggest lever on the whole project. Getting a working, field-tested TAK
client for free meant the port was a UI-and-SDK exercise rather than a protocol exercise. The
channel-scoping design in particular (`sendCot` injecting `<marti><dest group=…>` for every
selected channel) meant "broadcast markers to the pilot's active channels" cost us literally
zero new code. Thank you for that — it's cleanly separated in a way that's genuinely rare.

The same core is what the Autel port uses, which made that tree a useful second reference.

## What had to be rewritten

| Area | Why |
|---|---|
| **All SDK access** | V4 callbacks vs V5 `KeyManager`. `DroneTakBridge`, `ExposureController`, `FlightLimitsController`, camera/gimbal/battery/signal are all new against V4. |
| **Map layer** | Your V5 build uses DJI uxsdk mapkit; the Autel port uses osmdroid; we're on **MapLibre**. Ports of `TakMapMarkers`/`TakDropMarkers` are structurally different — one `GeoJsonSource` + `SymbolLayer` with bitmaps registered as named style images, rather than N `Marker` objects in an overlay list. Hit-testing is `queryRenderedFeatures` instead of per-marker click listeners. |
| **Video decode** | V4's `VideoFeeder` hands you ~2KB non-aligned chunks. We wrote a custom MediaCodec pipeline with an Annex-B NAL assembler, bounded queue, newest-frame-only render. This was by far the most fragile part of the project. |
| **RC button mapping** | `RcButtonManager` didn't port — it's written against V5 key-values. We ended up not needing it: the toolbar/HUD we built covers everything applicable to the Mini 2, and several of its actions (lens switch, thermal palette) are M30T-only anyway. |
| **AR overlay** | `ArOverlayView` not ported. Same V5 API problem, and your own header calls it a prototype. Deferred. |

Your icon generation, `iconKeyFor` cache scheme, 2525 type parsing (`milMarkerRes`), and the
`Ui` callback pattern for keeping dialogs in the Activity all ported over as pure logic. The
2525 affiliation drawables came straight from the Autel tree.

---

## Changes we made inside the shared taklite core

These are the ones that matter to you, since they're in code you own and some are arguably bugs
rather than V4-specific needs.

### 1. `sendMarker` mints a new uid every call — markers can't be moved

This is the one I'd most encourage you to look at. In `TakManager`:

```java
public String sendMarker(...) {
    String markerUid = "marker-" + UUID.randomUUID().toString().substring(0, 8);
    ...
}
```

Same in `sendMarkerToMission`. Since in CoT **the uid _is_ the marker's identity**, there's no
way for a caller to update a marker it previously sent — re-sending a moved marker spawns a
second marker on every other TAK client instead of moving the original. We hit this the moment
we tried to build "move this marker to where the camera is now pointing."

Worth noting the fix is entirely above `CotBuilder` — `buildMarker` already takes `markerUid`
as a parameter, so the uid is simply being generated one layer too high. We added:

```java
sendMarkerWithUid(uid, lat, lon, alt, affiliation, name, remarks[, mission])
```

and made `sendMarker`/`sendMarkerToMission` thin wrappers that generate a uid via a new
`newMarkerUid()` and delegate. Fully backward compatible — no existing caller changes.

**This affects your V5 build and the Autel port identically.** If you want the diff, it's small.

### 2. Dropped-marker stale time

`buildMarker` used the shared `STALE_DURATION_MS` (5 min, matching the ATAK default). For
pilot-dropped markers that's far too short — a marker dropped on something worth marking
vanishes from the shared picture before anyone can act on it. We split it out:

```java
MARKER_STALE_DURATION_MS = 14h   // dropped markers only
```

14h was our operator's call: long enough to survive an incident, short enough to self-clear
before the next shift. Deliberately scoped to dropped markers — `DRONE_STALE_DURATION_MS` and
the SPoI stale are untouched, they want short lifetimes.

(We also raised `DRONE_STALE_DURATION_MS` from 15s to 2 min for our own use — 15s made the
drone track flicker out on brief telemetry gaps. That one's more a matter of taste.)

### 3. `CameraSlantPoint.GroundPoint` discards the elevation it computes

```kotlin
data class GroundPoint(val lat: Double, val lon: Double, val rangeMeters: Double)
```

The terrain-iteration loop computes `targetElev` internally and then throws it away, so
everything downstream gets a ground point with no height. We added an `elevationMeters` field
populated from the converged elevation (and from the flat-ground fallback), which is what lets
dropped markers carry a real altitude instead of `0.0`. Low-risk additive change.

### 4. `CameraSlantPoint` intersected the ray against the wrong ground plane

**This one is a real bug and it is probably in your tree too — worth a look.** The slant
calculation used the aircraft's takeoff-relative altitude as its height above the ground *below
the target*. Over flat terrain those are identical and nothing is visibly wrong; over any
elevation change the ray meets the wrong plane and the computed ground point walks off.

Our pilot caught it as "the SPoI sits about 2.5° below the crosshair" — and the giveaway was
that a dropped marker landed exactly on the SPoI in CloudTAK. The marker placement was fine; the
SPoI it was placed at was wrong, so the two agreed with each other and disagreed with the world.

```kotlin
val effectiveAgl = if (aircraftMslMeters != null) {
    aircraftMslMeters - targetElev              // true MSL: difference directly
} else {
    aglMeters + (groundElevAtAircraft - targetElev)   // same correction, takeoff-relative
}
```

A pleasant side effect: differencing two DTED MSL samples **cancels the geoid offset**, so this
path needs no MSL-vs-WGS84 conversion at all.

---

## What we added on top

Roughly in order of how useful they turned out to be.

**Terrain / DTED (`DtedStore`, `DtedIndex`, `DtedTile`, `TerrainDatabase`)** — ATAK-style zip
import of DTED tiles with Room-backed region management, plus a real binary DTED reader with
bilinear interpolation. This feeds `CameraSlantPoint`, and terrain-corrected SPoI produced a
field-visible accuracy improvement, especially at shallow look angles.

**Terrain-corrected AGL (`TerrainAgl`)** — converts DJI's takeoff-relative altitude into true
height above the ground under the aircraft:

```
correctedAgl = takeoffRelativeAlt + (dtedElevAtTakeoff − dtedElevUnderAircraft)
```

Deliberately a **difference of two DTED samples**, so the MSL-vs-WGS84 geoid offset cancels
rather than needing correction. Two things we learned the hard way and baked into comments: the
takeoff terrain reference must be **latched once** (the pilot can move the home point mid-flight
via a long-press, and re-reading it would silently corrupt the correction by the terrain delta),
and the HUD label has to move with the correction — it reads `AGL` when corrected and `ALT` when
not, because labelling an uncorrected figure "AGL" is precisely the error being fixed. An MSL
line sits under it, which needs only the takeoff reference and so is available more often than
the AGL correction itself.

**FAA UASFM airspace ceilings (`UasfmStore`, `UasfmIndex`, `UasfmDatabase`)** — downloads the
FAA's published UAS Facility Map ceilings for an area and shows the limit where you're flying.
The useful finding here: **every UASFM cell sits on a fixed 30 arc-second (1/120°) grid**, which
we verified against real cells in two separate parts of Alaska. That means no polygon storage
and no point-in-polygon test — the lookup is `floor(lat*120)`, `floor(lon*120)`, and only
`(row, col) → ceiling` needs persisting. A statewide dataset is ~26k rows and fits comfortably
in a HashMap. Advisory display only; nothing is pushed to the aircraft's limits.

**RTSP video push** — Mini 2 video → MediaMTX → ATAK/CloudTAK. Landed as on-device
**MediaProjection screen-capture transcode** with Low/Standard/High profiles. Note for anyone
trying this on an old toolchain: we had to **vendor the `com.pedro.rtsp` source** (RootEncoder,
Apache-2.0) because every recent tag targets JDK-17 bytecode, which AGP 4.2.2's D8/R8 flatly
cannot dex.

**Signal-loss failsafe** — `setConnectionFailSafeBehavior` pushed on connect, with a read-back
logged so it can be verified without deliberately dropping the RC link in flight. Kept
deliberately distinct from the max-distance geofence, which only stops the aircraft at the
boundary; we chose *not* to add an app-side distance watchdog, since a firmware failsafe
survives the app dying and having two mechanisms share one name is a bad mental model in a
safety feature.

**Marker management** — list panel with move-to-crosshair / rename / retype / re-send / delete,
built on the uid-stable send above. Placement UX differs from yours by necessity: our mini-map
is locked (160dp, no gestures, operator spec), so there's no tap-to-place. The **camera
crosshair is the cursor** — the pilot aims the aircraft and the pin lands at the DTED-corrected
ground intersection. That turned out to be a nicer flow than tapping a map, and it's the thing
`CameraSlantPoint` was already perfectly positioned to provide.

**Other**: in-app debug log with export, Pre-Flight Setup screen (flight limits, map style, TAK
connection + channels, video, DTED, FAA), and a pilot-facing field guide.

---

## Where it stands

Phases 1–6 built and field-confirmed. Confirmed in the air: aircraft PLI + SPoI on a second TAK
client, marker drop/move/rename/retype/delete round-tripping correctly, 14h stale and
DTED-sourced elevation verified against raw CoT pulled from CloudTAK, terrain-corrected AGL
(watched it decrease as ground elevation rose — which also confirms the sign), aircraft-sourced
remaining-flight-time tracking with load, RTSP video playing cleanly in ATAK/CloudTAK, and 9
live TAK contacts rendering on the mini-map.

**Known limitations, honestly stated:**

- Geoid/ellipsoid offset on the SPoI is still uncorrected (DTED is MSL, DJI altitude is WGS84).
  Inherited, not introduced — and note the AGL correction above sidesteps it by construction.
- `BEARING_OFFSET_DEG` is still the value inherited from your M30T tuning, un-recalibrated for
  the Mini 2. Mitigated by preferring `yawRelativeToAircraftHeading`. A residual bearing error
  of roughly 8° survives the ground-plane fix above and is the largest remaining accuracy item.
- AR camera FOV is still the published Mini 2 spec, 73° × 45°. We built the calibration UI but
  never measured the airframe. Errors here are invisible at frame centre and grow outward.
- Static-scene video artifacting slowly accumulates and is cleared by a manual re-sync button.
  We investigated every "ask the aircraft for a keyframe mid-stream" avenue on V4 and none work;
  the 15s auto-resync we tried first was field-rejected because the freeze was worse than the
  artifacting.
- RC hotkey mapping not ported.

**Update — the AR overlay IS ported now**, and it works. Your `ArOverlayView` was the reference
and roughly 70% of the drawing came across as-is; the V5 `KeyManager` telemetry was replaced
with V4 callbacks. Three things we changed rather than ported, in case they are worth folding
back:

1. **Linear → perspective projection.** `dBearing / (hFov/2) * (w/2)` is a small-angle
   approximation; at 73° it drifts visibly toward the frame edges. We use gnomonic
   `tan(Δ)/tan(fov/2)`. The error is zero at centre, which is exactly what makes it easy to miss.
2. **Consume the letterboxed video rect**, not the view bounds — our FPV view pillarboxes, so
   drawing to full bounds is offset by the bar width and the offset moves with video aspect.
3. **Digital zoom must correct the FOV**, and not linearly: `atan(tan(halfAngle)/zoom)`. At 2×
   a 73° horizontal is ~41°, not 36.5°. Worth checking on your side independently of AR — ours
   was also publishing `sensorFov` pinned to the 1× width, so the FOV cone every other TAK
   client drew on its map was wrong whenever the pilot zoomed.

Field results: an iTAK client at ~500 yards rendered correctly, and a helicopter tracked at ~1
mile then visually acquired at 2× sat on the aircraft vertically.

---

## Thanks

The clean separation between `com/taklite/` and the SDK-specific layer is what made this port
practical for a small team on unfamiliar hardware. We spent our time on MapLibre rendering and
V4 video decode rather than on CoT, enrollment, or channel scoping — which is exactly how it
should have gone.

Happy to send diffs for any of the three taklite changes above, particularly the uid-stability
one, since that affects your V5 build and the Autel port unchanged.
