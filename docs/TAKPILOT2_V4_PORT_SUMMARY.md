# TAKPilot2 — DJI Mobile SDK V4 Port — Project Summary

*Consolidated 2026-07-30 from four separate plan docs (the original port plan, this summary,
and two Phase 6 sub-plans) into one reference. Snapshot, not a live view — cross-check against
the code, and `git log --oneline` for the real changelog. See `README.md` for the quick-start;
this doc is the deep reference: why things are built the way they are, what's field-confirmed,
what's still open. `docs/TAKPILOT2_FPV_ARTIFACTING_OPTIONS.md` is kept separate as an active
research note on the one significant unresolved issue.*

**Status: flyable, field-hardened, and in daily use.** Phases 1–6 (core flight, TAK CoT
telemetry, RTSP video push, map markers, AR overlay) are built and field-confirmed on a DJI
Mini 2 / RC-N1.

---

## 1. Why this exists

TAKPilot2 proper targets DJI Mobile SDK **V5**, which doesn't support the Mini 2 or any of the
older consumer airframes. This project needed the Mini 2 specifically, so it's a port onto
**MSDK 4.18** — not a literal port, since V4 has a completely different API surface
(callback/listener-based, vs. V5's `KeyManager`/`KeyTools` key-value model). Anything touching
the SDK had to be rewritten rather than moved; a large share of the UI is net-new, driven by
field testing. The toolchain is pinned correspondingly older: Gradle 6.7.1 / JDK 11 / AGP 4.2.2
/ Kotlin 1.5.10 (see README for the full build setup).

## 2. What ported unchanged — the reason this was feasible at all

The `com/taklite/` core (`TakManager`, `CotBuilder`, `TakCertEnroller`, `CotParser`, `TakUser`,
the Data Sync/mission client, `AppLog`) is **SDK-agnostic** and dropped in essentially as-is:
TLS CoT client, enrollment, contact tracking, and channel/`<marti>` scoping all worked
immediately. This was the single biggest lever on the whole project — it turned the port into a
UI-and-SDK exercise rather than a protocol exercise. The channel-scoping design in particular
(`sendCot` injecting `<marti><dest group=…>` for every selected channel) meant "broadcast
markers to the pilot's active channels" cost zero new code. The same core is what the Autel
sibling port uses.

## 3. What had to be rewritten

| Area | Why |
|---|---|
| **All SDK access** | V4 callbacks vs. V5 `KeyManager`. `DroneTakBridge`, `ExposureController`, `FlightLimitsController`, camera/gimbal/battery/signal are all new against V4. |
| **Map layer** | V5 uses DJI's uxsdk mapkit; the Autel port uses osmdroid; this app uses **MapLibre**. `TakMapMarkers`/`TakDropMarkers` are structurally different from both — one `GeoJsonSource` + `SymbolLayer` with bitmaps registered as named style images, rather than N `Marker` objects in an overlay list. Hit-testing is `queryRenderedFeatures`, not per-marker click listeners. |
| **Video decode** | V4's `VideoFeeder` hands over ~2KB non-aligned chunks. A custom MediaCodec pipeline was written with an Annex-B NAL assembler, bounded queue, newest-frame-only render — by far the most fragile part of the project (see §6). |
| **RC button mapping** | `RcButtonManager` is written against V5 key-values and didn't port. Turned out not to matter: the toolbar/HUD covers everything applicable to the Mini 2, and several of V5's mapped actions (lens switch, thermal palette) are M30T-only anyway. |
| **AR overlay** | `ArOverlayView` didn't port directly (V5 API), but was later rebuilt against V4 — see §8. |

Icon generation, the `iconKeyFor` cache scheme, 2525 type parsing (`milMarkerRes`), and the `Ui`
callback pattern for keeping dialogs in the Activity all ported over as pure logic.

## 4. Changes made inside the shared taklite core

These live in code shared with the V5 app and the Autel port, so they're worth flagging back
upstream if you maintain either of those:

**`sendMarker` used to mint a new uid every call.** Since in CoT the uid *is* the marker's
identity, there was no way to update a previously-sent marker — re-sending a moved marker spawned
a duplicate on every other TAK client instead of moving the original. Fixed with
`sendMarkerWithUid(uid, lat, lon, alt, affiliation, name, remarks[, mission])`, with
`sendMarker`/`sendMarkerToMission` now thin wrappers that generate a uid via `newMarkerUid()` and
delegate. Fully backward compatible. `CotBuilder.buildMarker` already took `markerUid` as a
parameter — the uid was simply being generated one layer too high.

**Dropped-marker stale time split out.** `buildMarker` used to share the 5-minute
`STALE_DURATION_MS` with everything else — far too short for a pilot-dropped marker to survive
long enough to matter. Split into `MARKER_STALE_DURATION_MS` (dropped markers only), leaving
`DRONE_STALE_DURATION_MS` (raised separately from 15s, which made the drone track flicker on
brief telemetry gaps) and the SPoI stale untouched. **The three figures live in
`CotBuilder.java` and are not repeated here** — the marker time was written into this document
as 14h and stayed wrong for 12 days after the operator raised it.

**`CameraSlantPoint.GroundPoint` used to discard the elevation it computed.** The terrain-
iteration loop computed `targetElev` internally and threw it away. Added an `elevationMeters`
field populated from the converged elevation (and the flat-ground fallback), which is what lets
dropped markers carry a real altitude instead of `0.0`.

**`CameraSlantPoint` intersected the ray against the wrong ground plane — a real bug, likely
present in the V5 tree too.** The slant calculation used the aircraft's takeoff-relative altitude
as its height above the ground *below the target*. Over flat terrain those are identical and
nothing looks wrong; over any elevation change the ray meets the wrong plane and the computed
ground point walks off. Caught because a dropped marker landed exactly on a wrong Sensor Point of
Interest — the marker placement was fine, the SPoI it matched was wrong, so the two agreed with
each other and disagreed with the world. Fix:

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

## 5. Flight screen architecture

**`TAKPilot2GoFlightActivity.kt` + `activity_takpilot2go_flight.xml`:**

- **Toolbar**: hamburger (finish → home) · RTH (with a two-state icon for "home point set") ·
  TAK shield + status dot · battery gauge · GPS icon+count · signal bars ‖ Video Re-Sync
  (two-tone camera-in-arrows) · LIVE toggle · REC toggle. Connection/status icons grouped left,
  video controls right.
- **Mini-map** (160dp, MapLibre): `setAllGesturesEnabled(false)` — **deliberately locked**, no
  pan/zoom/rotate (operator spec), north-up, fixed zoom 15, recentered on the aircraft every
  500ms HUD tick. Layers bottom-up: red home→aircraft line (`#F44336`, gated on homeSet), cyan
  aircraft arrow, white home pin. Style pilot-selectable (Street default / Hybrid / Custom).
- **Exposure**: `ExposureController.kt` — PROGRAM mode + center-weighted metering forced on
  connect, with a hidden EV bias (currently +2/3 EV, invisible to the pilot slider) on top of a
  visible ±2.0 slider. Photo capture re-applies the same exposure settings afterward, since
  `PHOTO_SINGLE`/`VIDEO_NORMAL` flat-mode switches each carry separately-persisted exposure state.
- **Flight limits**: `FlightLimitsController.kt` — persisted-with-defaults (200 ft altitude /
  5280 ft distance / 150 ft RTH altitude), applied one-shot per connect, plus
  `setConnectionFailSafeBehavior` (RTH/Hover/Land on signal loss) pushed and read back on connect
  — deliberately a *separate* mechanism from the max-distance geofence, which only stops the
  aircraft at the boundary. No app-side distance watchdog was added on top; a firmware failsafe
  survives the app dying, and two mechanisms sharing one name is a bad model for a safety feature.
- **RTH long-press**: resets the aircraft's home point to the phone's current GPS fix (RC-N1 has
  no onboard GPS). Confirms first, showing the lat/lon about to be set.

**`DroneTakBridge.kt`** is the telemetry→CoT bridge and the single place every V4 SDK callback is
registered; a `Hud` snapshot is read by the flight screen every 500ms.

**Design and visual reference.** This document held a colour table and a shape-and-typography
paragraph. Both are removed. `../../../../TAKPILOT2-UI-SPEC.md` is the single source of truth
for colour, type, components and layout in all three applications, and this document has no
authority over any of it.

The removal was not tidying. By 2026-08-14 the table had drifted from the shipped tokens and
gave `#FFB74D` for "status amber (caution)". That value is `tp_state_unknown`; the caution
token is `#FF9800`. Caution and unknown are two states that this application deliberately
keeps apart, so the copy taught exactly the error the specification exists to prevent.

- Colour tokens: specification §6.1. In this tree, `res/values/takpilot_colors.xml`.
- Buttons and dialogs: §6.2 and §6.3.
- Type: §6.4. Dimensions and screen size: §7.

Logo: `takpilot2_logo.png` (400x400, shield and eagle), 100dp on home and 84dp on the flight
card.

---

## 6. Video pipeline

**On-screen decode** (`FpvTextureView.kt`) is a **custom MediaCodec pipeline**, not DJI's
`DJICodecManager`. Root cause: the Mini 2 emits **no periodic SPS/PPS/IDR in steady state** —
keyframes only on request (field-measured 112s gap without one). Design: Annex-B NAL reassembly
from VideoFeeder's ~2KB non-aligned chunks, a bounded queue, newest-frame-only render, LOW_LATENCY
+ realtime priority. Loss-hardening: a NAL that can't be fed due to a momentary codec stall is
**held and retried**, not dropped; queue overflow drops backlog but does not freeze/resync. Sync
lifecycle: `waitForSync` until an SPS arrives; while unsynced, keyframe requests escalate every
500ms to a **hard resync** (`resetDecoder`) after 3s — this escalation is what makes screen
lock/unlock and Home↔Flight navigation recovery work (~3.5s, field-proven).

**`IdrRequesterHolder.kt`** is a process-wide dormant `DJICodecManager` used *only* as the
keyframe-request lever — created once, never destroyed (destroying/recreating it wedged DJI's
native engine mid-flight, `Lightbridge: startStream videoCtlobjet == NULL`, only clearable by a
force-stop). Field-proven quirks, don't rediscover them: `resetKeyFrame()` works exactly **once
per process** then silently no-ops; `resetDecoder()` is a no-op while our decoder *thinks* it's
healthy but reliably forces SPS/IDR when called while genuinely unsynced (4/4). Root cause of
both: **the keyframe authority lives in DJI's own decoder, and our custom pipeline — which is the
thing detecting the errors — isn't it.** Every "seamless mid-stream refresh" scheme is therefore
structurally dead on this SDK/aircraft combination; recovery requires going through the brief
unsync flash, or fixing it outbound via re-encode. This finding is also why
`docs/TAKPILOT2_FPV_ARTIFACTING_OPTIONS.md` exists as a live research question — it explores
whether other apps sidestep this by making `DJICodecManager` the actual decoder (and therefore the
authority) rather than a side channel.

**Static-scene artifacting** (gradual buildup after RF packet loss, clears on motion) is an
accepted trade-off, mitigated by loss-elimination hardening (hold-and-retry on codec stall,
frame_num-gap-triggered auto-resync, downlink-recovery resync) plus a manual Video Re-Sync
toolbar button. A periodic 15s anti-artifacting timer was tried and **field-rejected**: the
0.6–3s freeze on every resync was worse than the artifacting it prevented.

**DJICodecManager-as-decoder — investigated and ruled out on the Pixel 8 Pro (Tensor).**
Feeding raw VideoFeeder bytes to `DJICodecManager` directly: decoder+output-surface recreation
every ~3.3s (keyframe starvation → jitter); a 2s `resetKeyFrame` pump stopped the resets but drove
`Codec_OSAL_DequeueBuf` failures → total freeze. The aspect-ratio transform was ruled out as the
cause. **This verdict may be Tensor-specific** — DJI's own apps had a documented, SDK-side-fixed
Pixel 6/Tensor video bug around the same SDK era — and was never retested on the MediaTek-based
RT3 field device. See the artifacting options doc for the retest plan. The experiment is preserved
on the `option1-video` git worktree/branch as a recorded negative result; don't re-attempt without
reading that doc first.

### RTSP video push (Phase 5) — built and field-working

Mini 2 live video → MediaMTX (RTSP) → ATAK/CloudTAK, playing cleanly. Protocol is RTSP/TCP
(RTMP rejected: extra remux hop, higher latency, worse ecosystem fit).

**RTSP client is vendored source**, not a Gradle dependency: `com.pedro.rtsp` (RootEncoder's rtsp
module, Apache-2.0, ~28 files, see its `NOTICE.txt`). The published artifacts target JDK-17
bytecode, which this tree's AGP 4.2.2 D8/R8 cannot dex — confirmed not a JitPack fluke. Compiling
the source against this project's Java-8 target sidesteps that permanently.

**Three video modes** (`DroneVideoStreamer.kt`, profile-driven):
- **Screen capture — the shipping path for all UI profiles.** `ScreenCaptureEncoder.kt`:
  MediaProjection mirrors the *whole* flight screen (FPV + HUD + map + toolbar, per operator
  spec) into a VirtualDisplay, straight into an H.264 encoder's input Surface. No second decoder
  (captures FPV's already-clean rendered pixels), GPU scaling. **Structurally immune** to the
  decode-transcoder's artifacting, since it never touches the raw aircraft NALs. Hosted by
  `ScreenCaptureService.kt`, a typed (`mediaProjection`) foreground service — MANDATORY ordering
  on Android 14: `startForeground()` first, then `getMediaProjection()`.
- **Decode-transcode** (fallback, kept in code, not exposed in UI): decode → downsample →
  re-encode. CPU downsample can't always keep up on scene changes → NAL-queue overflow →
  reference-chain artifacting, which is *why* the project pivoted to screen capture.
- **Passthrough** (code-only, removed from UI): raw aircraft NALs → RTSP, zero re-encode.
  Viewer-hostile given the no-periodic-IDR behavior above — a viewer joining mid-stream gets black
  video with no self-recovery. Kept only as a debug fallback.

**Quality profiles** (persisted, default Standard): Low 360p/10fps/275k · Standard
480p/15fps/550k · High 720p/15fps/1000k, aspect always preserved. Transcoded output publishes to
a `-Low`-suffixed path so the media server passes it through instead of re-transcoding.

**Two hard bugs, don't reintroduce:**
1. **RTSP packetizer never armed.** RootEncoder's `H264Packet.sendKeyFrame` is a one-shot that
   only latches on the first IDR it sees *while* `RtspSender.running` is true — but `connect()`
   is async, so the bootstrap IDR was sent before `running` flipped and got silently discarded,
   dropping every subsequent frame forever (feed shows "online," carries no video). Fixed by
   requesting a fresh keyframe in `onConnectionSuccessRtsp` on every connect, and re-arming after
   any reconnect.
2. **Transcoder NAL-drop artifacting** (decode-transcode path only): fed the whole burst then
   drained, dropping NALs once input buffers filled — same class of bug as the FPV pipeline's own
   loss-elimination problem. Fixed with hold-and-retry + interleaved drain.

**Field-verified:** Standard profile clean through busy moving scenes at 1068×480/15fps; Low
profile (800×360/10fps, ~218kbps) clean over a 5-min soak; High profile confirmed fine. **90-min
thermal soak at 480p passed** on the Pixel 8 Pro — phone only "slightly warm," no throttling, no
stream degradation; treat 480p/Standard as thermally safe for full-mission-length flights (longer
soaks and High-profile-at-length remain unmeasured).

**Known bug, unresolved:** no clean reconnect after a network drop. Repro: stream live, disable
Wi-Fi ~10s — RTSP dies correctly, LIVE pill flips to paused, but the projection/service stays
alive underneath. Re-enable Wi-Fi and tap LIVE: because the streamer's internal state never
transitioned to a clean "stopped" state on failure, the tap is read as *stop* rather than
*reconnect* — a second tap (re-requesting capture permission) is needed to actually resume. Fix
is either RTSP auto-reconnect with backoff while keeping the projection alive, or mapping a
post-failure LIVE tap to "reconnect" instead of "stop."

---

## 7. Terrain, altitude, and airspace

**DTED** (`DtedStore.kt`, `DtedIndex.kt`, `DtedTile.kt`, `TerrainDatabase.kt`): ATAK-style zip
import of DTED tiles with Room-backed region management, plus a binary DTED reader with bilinear
interpolation (verified byte-for-byte against a real Anchorage `.dt2`). Feeds
`CameraSlantPoint`; terrain-corrected SPoI produced a field-visible accuracy improvement,
especially at shallow look angles.

**Terrain-corrected AGL** (`TerrainAgl.kt`) converts DJI's takeoff-relative altitude into true
height above the ground *under the aircraft*:

```
correctedAgl = takeoffRelativeAlt + (dtedElevAtTakeoff − dtedElevUnderAircraft)
```

Deliberately a **difference of two DTED samples** (same dataset, same datum), so the
MSL-vs-WGS84 geoid offset that dogs the SPoI cancels out — this is also why it deliberately does
NOT use the SDK's own "above sea level" figure, which would reintroduce that exact problem. The
takeoff terrain reference is **latched once** at the first available home location and never
re-read — load-bearing, because RTH long-press lets the pilot move the home point mid-flight, and
re-reading from a moved home would silently corrupt the correction by the terrain delta between
the two points. The HUD label moves with the correction: `AGL` when DTED-corrected, `ALT` when
it's the raw takeoff-relative figure — both the AGL readout and the FAA exceeded-check consume the
same single `TerrainAgl.Reading` per tick, so they can never disagree with each other.
**Field-confirmed**: AGL readout decreased as the aircraft flew over rising terrain — the correct
sign (a sign error would plausibly look like AGL *increasing*, which is the failure mode worth
knowing about if this is ever touched again).

**MSL altitude on the HUD**: `takeoffTerrainElevMsl + heightAboveTakeoff` — needs only the takeoff
terrain reference, not terrain under the aircraft, so it's available in strictly more situations
than the AGL correction.

**FAA UASFM airspace ceilings** (`UasfmStore.kt`, `UasfmIndex.kt`, `UasfmDatabase.kt`): downloads
the FAA's published UAS Facility Map ceilings for an area and displays the limit at the aircraft's
position, advisory only — nothing is pushed to the aircraft's flight limits. **The load-bearing
finding**: every UASFM cell sits on a fixed **30 arc-second (1/120°) grid**, verified against real
cells in two separate parts of Alaska — so the lookup is `floor(lat*120)`, `floor(lon*120)`, no
polygon storage or point-in-polygon test needed, and a statewide dataset (~26–27k rows) fits
comfortably in a HashMap. Row/col are derived from each feature's centre coordinates, never
polygon corners (which carry enough floating-point noise to flip a boundary). `CEILING` of 0 is a
real, meaningful value ("no ops without further coordination"), not a null — don't "fix" that.

**A real bug worth knowing about if you ever touch the endpoint**: the FAA publishes multiple
layers that look similar (`FAA_UAS_FacilityMap_Data`, `..._Primary`, `..._V5`). The originally
shipped source (`_V5`, which reads like "newest") was actually a **stale snapshot** — it reported
0 ft in a real 200 ft cell, caught by an operator standing in a known cell and cross-checking
against the FAA's own viewer. The tell is the `MAP_EFF` (map effective date) field — any layer
returning a non-current effective date for a spot-checked cell is the wrong layer. Fixed by
switching to the un-suffixed `FAA_UAS_FacilityMap_Data` layer.

**Known limitation**: the geoid/ellipsoid vertical-datum offset between DTED (MSL) and DJI's
altitude (WGS84 ellipsoid) is not corrected for the SPoI — tens of meters possible,
location-dependent. `BEARING_OFFSET_DEG` is still 0 (inherited from a V5/M30T tuning value of
+105° that doesn't apply here), mitigated by preferring the SDK's relative-yaw field over raw yaw
when available. A residual SPoI bearing error of roughly 8° remains after the ground-plane fix in
§4 — this is the single largest remaining accuracy item.

---

## 8. Markers, Data Sync, and the AR overlay (Phase 6)

**The single most important finding from porting this phase: it's a map-rendering port, not a
networking port.** The entire TAK protocol side (inbound CoT → contact objects, uid-stable
marker send, feed-scoped publish) already existed and worked — nearly all the work was drawing
things on MapLibre and building pilot UX around it.

### Inbound contacts + dropped pins

`TakMapMarkers.kt` renders inbound TAK contacts on the mini-map (`GeoJsonSource` + `SymbolLayer`,
following the same idiom the aircraft/home markers already used) — team-colored dots for PLI,
MIL-STD-2525 affiliation frames for placed markers, greyed on staleness, layered under the
aircraft/home markers.

`TakDropMarkers.kt` implements the drop workflow. **Design decision: the mini-map stays locked**
(no pan/zoom/interactivity — that's explicitly a future item), so pins are placed via the
**camera crosshair** using `lookPoint()` — the same DTED-terrain-corrected ground-intersection
point the SPoI uses. Flow: toolbar button → prompt (4-way affiliation picker: Friendly `a-f-G`
blue rectangle / Hostile `a-h-G` red diamond / Neutral `a-n-G` green square / Unknown `a-u-G`
yellow quatrefoil, plus an editable pre-filled name) → accept draws locally and broadcasts on the
pilot's active channels for free (via the existing `<marti>` channel-scoping). Auto-naming is
`<callsign>-P<n>`, incrementing, with the counter only consumed if the pilot accepts the
suggested name unchanged (a custom name doesn't burn a number and leave a gap). A `lookPoint()`
of null (no GPS/gimbal fix yet) refuses the drop with a toast rather than placing at a garbage
location.

**Marker management**: long-pressing the drop-pin button opens a list panel (one row per dropped
pin, with range/bearing from the aircraft) with row actions Move-to-crosshair / Rename / Change
type / Re-send / Delete — all built on the uid-stable send from §4, so they move/update the
marker in place rather than duplicating it. **Delete is local-only** (TAK's default behavior,
matches operator expectation) — no delete CoT is sent, and the deleted uid is **not** suppressed,
so if the server or another client re-echoes that marker it's expected to legitimately reappear as
ordinary inbound traffic; that's by design, not a bug. Inbound markers get the same treatment in
reverse: tap-to-hide locally (map-only, stays live on the server for everyone else) via
`queryRenderedFeatures` hit-testing, confirmed to coexist with the locked map's gesture-disabling.

**Field-confirmed** (2026-07-26/27): all four affiliations dropped and rendered correctly on a
second TAK client; move/rename/retype/re-send/delete exercised via the list panel; the marker
stale time verified against raw CoT pulled from CloudTAK (`start` and `stale` exactly one
`MARKER_STALE_DURATION_MS` apart — 14h at the date of that test, 72h since 2026-08-02); restart
persistence; DTED-sourced elevation on drops (`altsrc: "DTED0"`, not the old hardcoded 0.0).
**Not yet exercised**: feed-scoped Data Sync publish end-to-end, and a live 2525 marker-frame
render from another client (only plain-dot PLI seen live so far — the icon path itself is
implemented and was verified via the mini-map's own rendering).

### AR overlay

Projects inbound TAK contacts, this app's own dropped pins, and ADS-B air tracks onto the live
FPV video, pinned to the world. **Built and field-confirmed**, including at range: an iTAK client
rendered correctly at ~500 yards, and a helicopter tracked at ~1 mile then visually acquired at 2×
zoom landed on the aircraft vertically — validating the reported-altitude path, aircraft category,
and zoom-corrected FOV together in one test.

The camera-pose model AR needs already existed and was field-proven by the crosshair marker drop
(`cameraBearing()` + `lookPoint()`), so **the centre of frame was correct by construction**; the
genuinely new work was the off-axis projection. The self-test that made this cheap to validate: a
pin dropped at the crosshair is by definition at the camera's look point, so it must render
dead-centre under the crosshair — any offset is a projection, pose, or video-rect bug, provable on
the ground with no flying or second client needed.

**Three things the V5 reference did that were deliberately fixed, not ported as-is:**

1. **Linear → perspective projection.** V5 maps angle to pixels linearly
   (`dBearing/(hFov/2) * (w/2)`) — a small-angle approximation that's visibly wrong toward the
   frame edges at the Mini 2's 73° FOV, and the error is zero at centre, which is exactly what
   makes it easy to miss on a quick check. Replaced with gnomonic (perspective) projection,
   `tan(Δ)/tan(fov/2)`.
2. **Consume the letterboxed video rect, not the view bounds.** This app's `FpvTextureView`
   pillarboxes the video inside the view; drawing to full view bounds offsets the overlay by the
   bar width, and the offset moves with video aspect.
3. **Digital zoom must correct the FOV non-linearly**: `atan(tan(halfAngle)/zoom)`. At 2× a 73°
   horizontal FOV becomes ~41°, not 36.5° (the linear halving). This was worse than an AR-only
   bug: the same unconditional FOV constant was also being published as `sensorFov` in the
   aircraft's own CoT, so the FOV cone drawn on *every other* TAK client's map was pinned to the
   1× width whenever the pilot zoomed. Fixed by making zoom correction shared, so the AR overlay
   and the published cone can't disagree.

**Target elevation** uses real DTED terrain under the contact when available (rather than V5's
flat-plane assumption, which put contacts at the takeoff elevation) — `Δz = targetGroundMsl −
aircraftMsl`, falling back to the flat-plane assumption when there's no DTED coverage. The app's
own dropped pins already carry DTED-derived altitude and use it directly. Contact altitude itself
remains an open question — `reported` (the contact's own CoT `hae`, WGS84 ellipsoid) vs. `terrain`
(DTED ground elevation under the contact, self-consistent MSL) are both computed and logged every
frame; the field picks the winner based on which puts the icon on the actual person.

**Category toggles**: three, not two — "my dropped markers" / "other users' placed 2525 markers"
/ "other users' raw PLI positions" — persisted independently, default all ON, reached via a
long-press options dialog on the AR toolbar button (same idiom as RTH long-press and the drop-pin
long-press). Splitting inbound *markers* from inbound *PLI* matters because a dozen people's
position dots are what carpets a busy video feed, while their deliberately-placed markers are
usually the thing worth seeing — collapsing those into one toggle would force losing both
together. This is orthogonal to, and stacks with, the existing per-uid local-hide from §8's
marker management (a marker only draws if it passes both).

**ADS-B air tracks** render as their own category: distinct aircraft glyph rotated to CoT
`course`, a much larger range cap than ground markers (15 nm), altitude shown in the label since
vertical separation is what matters for air traffic. Tracks lag reality by up to ~10s (the
upstream gateway's poll interval, not an app bug) — dead-reckoning from CoT `course`+`speed` would
fix it but isn't built (`CotParser` currently discards both fields). The app's own SPoI marker is
filtered out of the inbound picture via `isOwnPublishedUid` — without that filter it would render
permanently pinned under the crosshair, since the SPoI is by definition wherever the camera points.

**Calibration mechanism shipped, values not yet measured.** `TakBridgeHolder` holds an adjustable
1× `hFovBase`/`vFovBase` (clamped 5–170°), persisted with a reset-to-spec option, with stepper
controls in the AR options dialog. The stored values are still the published Mini 2 spec (73° ×
45°) — nobody has measured the actual airframe. An FOV error is invisible at frame centre and
grows toward the edges, so the settling test is a marker near the frame edge with the FOV stepped
until it lines up. Keep the projection diagnostic trace (`ArOverlayView.diag()`, one line per pin
per second logging bearing/pitch deltas, FOV in use, and drawn pixel or off-frame) until this is
resolved — it's the instrument the calibration needs, and it's what turned the original off-axis
projection bug from guesswork into a five-minute diagnosis.

**Crosshair accuracy cue**: the reticle's centre ring tints by gimbal pitch (green ≤ −25°, amber
−25° to −10°, white shallower) since ground-point error from pointing error scales as
`1/sin²(pitch)` — thresholds were tuned from field results (a theoretical −30° cutoff left the ring
amber during perfectly good drops), giving roughly ±10 ft error in green, ±50 ft in amber. Only the
ring tints; the reticle arms stay white so it still reads as a stable sighting reference.

---

## 9. Signal-loss failsafe, debug logging, and pilot support

**Signal-loss failsafe**: `setConnectionFailSafeBehavior` (RTH/Hover/Land, default RTH) is pushed
on every connect with a read-back logged for verification without deliberately flying out of
range. Kept distinct from the max-distance geofence by design (see §5).

**In-app debug logging** (`AppLog.kt`, ported from the Autel sibling's dev-notes design):
vendor-neutral facade, always forwards to logcat, optionally also writes to a rotated file sink
(`filesDir/logs/app.log`, 1MB-rotated/2h-swept) plus a capped export archive
(`Downloads/TAKPilot2 Logs/`, 10MB), with a crash handler chained in at app startup. `DebugActivity`
provides Standard/Detailed toggles, a live tail, export via FileProvider, and Clear/Delete. A
"Include TAK/CoT logs" filter (default ON) can drop the noisy 2s-interval CoT push tags from the
file sink without affecting logcat — deliberately an explicit tag allowlist rather than a prefix
match, since a naive `Tak`-prefix rule would wrongly eat app-side screens like
`TAKPilot2GoHomeActivity`/`TakConnectActivity`.

**Pilot Field Guide** (`FieldGuideActivity.kt`): five sections (what the app is for, the
Pre-Flight Setup screen section-by-section, the flight screen control-by-control, flight path
records, and what this build cannot do), written for
pilots rather than developers — no class names, no SDK talk, and limitations that affect a flight
decision stated plainly (local marker delete ≠ deleted for everyone, the FAA layer is advisory
only, AGL vs. ALT, the geofence stops rather than returns). Icon examples in the guide are **live
views, not screenshots** — the actual widgets constructed and driven into the described state —
so they can't go stale independently of the real UI.

⚠ **The icons cannot go stale. The words can, and did.** On 2026-08-13 the crosshair gestures
changed and the guide kept describing the old ones for a day. The Field Guide is documentation
that ships: it changes in the same change as the behaviour it describes. Specification §8.2
rule 7.

---

## 10. Known limitations, honestly stated

- **Static-scene video artifacting** persists as an accepted trade-off, mitigated but not solved
  (see §6 and the separate `TAKPILOT2_FPV_ARTIFACTING_OPTIONS.md` for active research on this).
- **Geoid/ellipsoid vertical-datum offset** on the SPoI is uncorrected (see §7); the terrain-
  corrected AGL sidesteps it by construction, but the SPoI itself doesn't.
- **`BEARING_OFFSET_DEG`** is inherited/un-recalibrated for the Mini 2; a residual ~8° SPoI
  bearing error remains after the ground-plane fix.
- **AR camera FOV** is still the published spec, never measured against the real airframe (§8).
- **RC hotkey mapping** was not ported — not needed, the toolbar/HUD covers the applicable
  actions for this airframe.
- **RTSP reconnect after a network drop** needs two taps instead of one (§6).
- **A safety-relevant, unresolved incident**: on one field flight, `startGoHome()` (RTH) failed
  three consecutive times with a DJI-side "process timed out" error, requiring the pilot to fly
  the aircraft home manually. The SDK call was made each time; the *aircraft* didn't acknowledge
  it (~3s each attempt). Not confirmed to be an app bug — open questions are whether it
  reproduces, whether it's tied to the specific test hardware's USB-to-RC-N1 command path, or to
  the aircraft's battery state at the time (it was at/near critical battery, which may have
  already engaged the aircraft's own low-battery RTH logic and rejected a redundant command) —
  but since the app is what the pilot taps, this needs to be understood before RTH is fully
  trusted in a real emergency. Note the aircraft's own *failsafe* RTH
  (`setConnectionFailSafeBehavior`, §9) is a separate mechanism and not implicated by these
  timeouts. **Investigate before relying on pilot-initiated RTH as a sole recovery plan.**

---

## 11. Environment / build (see also README)

Git layout: `main` branch, tag `baseline-current` at the pre-fork snapshot; a second worktree
holds branch `option1-video` (the DJICodecManager experiment from §6, kept as a recorded negative
result). Toolchain: Gradle 6.7.1 / JDK 11 / AGP 4.2.2 / Kotlin 1.5.10 (matters for the RTSP
vendoring decision in §6). Build from the repo root:

```bash
ANDROID_SDK_ROOT=<path-to>/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 ./gradlew :app:assembleDebug
```

**DJI API key is bound to the applicationId**, not the manifest package — changing it (flavors,
suffixes, side-by-side installs) breaks aircraft registration unless a second key is registered.
A/B testing is therefore build-and-swap from git branches, not parallel installs. See README for
how the key itself is kept out of version control.

**Hardware-connection staleness gotcha**: after repeated force-stop/relaunch, DJI's product-
connection session can go stale — unplug the RC-N1 USB-C, wait ~10s, replug, *then* force-stop and
relaunch.

## 12. Sibling references

**Autel port** (`Autel/AutelTAKPilot2/`) — same `com/taklite/` TAK/CoT core, different drone SDK
(Autel Mobile SDK, EVO II 640T V3 / Smart Controller V3). The debug-logging facade (`AppLog`) was
originally harvested from that project into this one. See that project's own plan doc for its
current status, which as of this writing targets bringing its UI up to parity with everything
documented above.
