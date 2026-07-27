# TAKPilot2 Go V4 — Phase 6D Plan: AR Overlay (CoT markers on the FPV)

*Drafted 2026-07-26 from a code audit of this tree and the V5 `ArOverlayView` reference.
Companion to `TAKPILOT2_V4_PORT_PLAN.md` — read that doc's START HERE + Environment first.
This one only covers 6D.*

---

## START HERE (new chat)

**Goal:** inbound TAK contacts and our own dropped pins are projected onto the live FPV video,
pinned to the world, so the pilot can see *which thing on screen* a marker refers to.

**Status 2026-07-27: A, B, C, B2 all BUILT and FIELD-CONFIRMED. D BUILT — but see the caveat.**

**D shipped the calibration *mechanism*, not calibrated *values*.** `TakBridgeHolder` now holds
an adjustable 1x `hFovBase`/`vFovBase` (clamped to 5–170°, since an FOV near zero sends every
marker to infinity), `ArSettings` persists it across flights with a reset-to-spec option, and
the AR options dialog exposes stepper controls. **The stored values are still the published
Mini 2 specs, 73° × 45° — nobody has measured this airframe.** An FOV error is invisible at
frame centre and grows toward the edges, so the field test that settles it is: put a marker on
a recognisable object near the frame EDGE and step the FOV until the icon sits on it.

Field results:
- **A** — crosshair self-test passes; a dropped pin renders under the crosshair.
- **B** — an iTAK client at ~500 yards rendered correctly.
- **B2 / zoom** — a helicopter tracked at ~1 mile, then visually acquired at 2x with the marker
  **on the aircraft vertically**. That single test validates the reported-altitude path, the
  aircraft category, and the zoom-corrected FOV together.

**Known and expected: ADS-B tracks lag reality by a few seconds.** The operator's gateway polls
Airplanes.live every `POLL_SEC=10`, so a position can be up to ~10 s old before it even reaches
TAK. At 83 kt that is ~400 m of travel. This is a property of the feed, not the overlay.
*Possible future fix:* dead-reckon the track forward using the CoT `course` + `speed` fields
scaled by the age of the last update — but `CotParser` currently discards both, so it would need
a parser change, and extrapolation goes wrong through turns. Not attempted.

### Zoom breaks the projection unless the FOV is corrected — fixed 2026-07-27
Digital zoom is a centre crop, so the angular width shrinks with the zoom factor and **not
linearly**: `effectiveHalfAngle = atan(tan(baseHalfAngle) / zoom)`. At 2x the 73° horizontal
becomes **~41°, not 36.5°** — the linear approximation would leave markers a few degrees out at
the frame edges, the same class of error as the linear-projection bug fixed in A.

This was worse than an AR bug: `DroneTakBridge.pushCameraPoint` published `sensorFov =
MINI2_HFOV` unconditionally, so **the FOV cone drawn on every other TAK client's map was also
pinned to the 1x width**. Zoom now lives in `TakBridgeHolder` and feeds both the published cone
and the overlay, so the two cannot disagree.

### The SPoI sat ~2.5° below the crosshair — fixed 2026-07-27
Found by the operator, not by us: a pin dropped at the crosshair landed visibly below it in the
FPV, while CloudTAK showed the marker exactly on the SPoI. So the *marker* was right and the
*SPoI itself* was wrong — the two just happened to agree.

`CameraSlantPoint` was treating the aircraft's takeoff-relative altitude as its height above the
ground *directly below the target*. Over flat ground those are the same number and the bug is
invisible; over any elevation change the ray is intersected against the wrong plane. Now, when a
true MSL altitude is available it is differenced directly against the target's terrain elevation:

```kotlin
val effectiveAgl = if (aircraftMslMeters != null) {
    aircraftMslMeters - targetElev
} else {
    aglMeters + (groundElevAtAircraft - targetElev)
}
```

The fallback branch is the same correction expressed relative to the takeoff point, for when no
MSL figure exists. **Differencing two DTED MSL samples also cancels the geoid offset**, so this
path needs no MSL-vs-WGS84 conversion.

After the fix a drop lands under the crosshair. A residual bearing error of roughly 8° remains —
a pitch/bearing trim was proposed to attack it and was **not** built.

### Crosshair accuracy cue — added 2026-07-27
Ground-point error scales as `1/sin²(pitch)`: at ~40 m up, roughly 4.5 ft of ground error per
degree of pointing error at −45°, 19 ft at −20°, 65 ft at −10°. The pilot had no way to see
that, so the reticle's centre ring is now tinted from gimbal pitch — green at ≤ −25°, amber
−25° to −10°, white shallower. Thresholds live in `CrosshairView.PITCH_GOOD_DEG` /
`PITCH_FAIR_DEG` and are shared with the HUD's gimbal readout, so the number and the ring cannot
disagree about what state the pilot is in. Only the ring is tinted; the arms stay white so the
reticle reads the same as a sighting reference in every state.

Both thresholds were **set from field results, not theory** — −30 was stricter than the hardware
warrants and left the ring amber during perfectly good drops at ~100 ft AGL and −20°. Rough
figures with good GPS and DTED: **±10 ft in green, ±50 ft in amber.**

**Aircraft labels show RELATIVE altitude** (`+2400 ft`), not MSL. Besides being the question a
pilot is actually asking, it makes the display self-checking — a track labelled `+2400 ft` near
the horizon is visibly wrong, where `2900 ft` looks plausible wherever the icon lands.

**A missing `aircraftMsl` now logs loudly.** Without it every vertical angle silently degrades to
a flat-plane estimate, which puts air traffic at the pilot's own level — a failure that looks
like "AR works but heights are wrong" rather than like a missing input.

**Open question B leaves behind — contact ALTITUDE source.** Two computations run every frame
and both are logged side by side; the field decides which to keep:
- **`reported`** (currently primary, operator's call): the contact's own CoT altitude.
  `CotParser` reads it from the `hae` attribute — WGS84 ELLIPSOID — while the aircraft figure is
  MSL. The geoid separation is order 10-15 m locally: negligible at 1 km, roughly 18° of
  elevation at 30 m. **If contacts render systematically LOW and it worsens as you close on
  them, that is this.**
- **`terrain`** (automatic fallback when a contact publishes no altitude): DTED ground elevation
  under the contact. Self-consistent MSL, no datum mixing, accurate to about a person's height
  for anyone on foot — and simply wrong for anything off the ground. This is what V5 settled on
  after finding reported altitude made contacts "float in the sky and slew as the gimbal tilts".

Look for `dzReported=… dzTerrain=… elevReported=… elevTerrain=…` in the trace; whichever puts
the icon on the actual person wins.

**The pose model, the perspective projection and the video-rect handling are all validated.**
The projection trace proved it before the pin was ever visible: with the camera 2.2° off the
pin (`dBrg=2.2`), it computed `drawn at 942,236` against a video rect of `0,0-1792,1008` —
right of centre and above it, exactly correct for a pin 3.4 m ahead with the camera pitched
down 16.8°. That means B (inbound contacts) is plumbing, not maths.

### Two bugs found during A — both worth knowing before touching this file

1. **Range guard was on GROUND distance, not slant range.** Aiming steeply down (i.e. the
   normal way to drop a marker on something below) collapses ground distance toward zero while
   the target is still tens of metres away, so a near-nadir pin was rejected before it was ever
   projected — the exact case the self-test most naturally lands in. Now guards on
   `hypot(groundDist, dz)`.
2. **`BitmapFactory.decodeResource` returns null for VectorDrawable XML.** The affiliation
   markers are vectors, so `drawPin` bailed out on a `?: return` and painted nothing while the
   projection logged a correct on-screen position. **The single most misleading failure mode
   available: everything upstream reporting success, nothing visible.** Must rasterise via
   `TakMapMarkers.drawableToBitmap` (`ContextCompat.getDrawable` onto a Canvas) — the path the
   mini-map has always used, which is precisely why markers appeared there and not in AR.
   Rasterise failure now logs loudly rather than returning silently.

### The projection trace

`ArOverlayView.diag()` logs one line per pin per second while AR is on: camera bearing vs pin
bearing, camera pitch vs pin elevation, the deltas, the FOV in use, and either the drawn pixel
or `OFF-FRAME`. It is what turned this from guesswork into a five-minute diagnosis, and it is
the instrument the FOV calibration in D needs — **keep it until D is done.** Throttle is per
draw pass, not per call; a per-call throttle only ever logs whichever pin is first in the list
and hid a second marker's trace entirely during troubleshooting.

*Also confirmed correct-by-rejection:* a second pin at `dBrg=45°` against a 36.5° half-FOV was
properly discarded as off-frame. Not every "missing" marker is a bug.

*New in this tree for A:* `DroneTakBridge.CameraPose` + `cameraPose()`, `hFovDeg()`/`vFovDeg()`
accessors, `TakBridgeHolder.cameraPose()`, `takpilot2/ArOverlayView.kt`, the `flightArButton`
toolbar pill, and the overlay wired beneath the crosshair in the flight layout.

**The single most important finding of the audit — this is far less risky than "6D deferred"
implies.** The camera-pose model AR needs already exists in this tree and is **field-proven**:
`DroneTakBridge.cameraBearing()` + `lookPoint()` are what place a dropped marker at the
crosshair, and the operator has confirmed in flight that those land where they should. AR reuses
that same pose, so **the centre of frame is correct by construction**. The genuinely new,
unproven part is the *off-axis projection* — everything in the test plan below is aimed there.

**The self-test that makes this cheap to validate:** a pin dropped at the crosshair is BY
DEFINITION at the camera's look point, so it must render dead-centre under the crosshair in AR.
Any offset is a projection, pose, or video-rect bug. No second TAK client, no survey point, no
flying, no external truth needed. Build to this test first (Sub-phase A).

**Expectation to hold, stated up front:** V5's own header calls this *"good enough to 'find
someone,' not survey grade."* That is the right bar. Accuracy is dominated by gimbal bearing
accuracy and telemetry-vs-video latency, neither of which this app controls. It answers "which
of those buildings is the contact in," not "what are the contact's coordinates" — the crosshair
drop already does the latter, better.

---

## What already exists (verified by audit, do NOT rebuild)

| Capability | Where | Status |
|---|---|---|
| True camera bearing (heading + gimbal relYaw, with fallback) | `DroneTakBridge.cameraBearing()` | works, field-proven via SPoI/marker drops — **currently `private`** |
| Camera pitch | `DroneTakBridge.lastGimbal.attitudeInDegrees.pitch` | works — **not exposed on `Hud`** |
| Mini 2 FOV constants | `DroneTakBridge.MINI2_HFOV = 73.0`, `MINI2_VFOV = 45.0` | estimates, **need calibration** (see D) |
| Bearing/distance geodesy | `CameraSlantPoint.initialBearingDeg()`, `.distanceMeters()`, `.norm360()` | public, works |
| Aircraft MSL altitude | `TerrainAgl.Reading.mslMeters` | works, field-confirmed |
| Terrain elevation anywhere | `DtedIndex.elevationAt(ctx, lat, lon)` | works |
| Inbound contacts | `TakManager.getInstance().takUsers` | works |
| Locally-hidden marker set | `TakMapMarkers.isHidden(uid)` | public, works |
| Our dropped pins | `TakDropMarkers.listPins()` → `PinInfo(key, name, affiliation, lat, lon, alt)` | works |
| **Letterboxed video rect** | `FpvTextureView.onVideoRectChanged` → `CrosshairView.setVideoRect()` | works — **AR must consume the same rect** |
| 2525 frame drawables | `marker_{friendly,hostile,neutral,unknown}.xml` | present |

### Plumbing that must be ADDED before any drawing works

1. **Expose the camera pose.** `Hud` carries `gimbalPitch` but **no gimbal yaw and no camera
   bearing**, and `cameraBearing()` is private. Add a `TakBridgeHolder.cameraPose(): CameraPose?`
   returning `(bearingDeg, pitchDeg)` computed by the existing proven model. Prefer this over
   leaking raw yaw and re-deriving the bearing in the view — re-deriving means two copies of a
   safety-relevant model that can silently diverge.
2. **Share the icon/colour helpers.** `TakMapMarkers.milMarkerRes()` and `.teamColor()` are
   `private`. AR needs both. Make them internal/public on `TakMapMarkers` (or lift to a small
   shared helper) rather than copy-pasting — the V5 file duplicates them and that is exactly how
   the map and the AR view end up disagreeing about what colour a team is.

---

## Reference implementation, and the three things NOT to port

`TAKPilot2-DJI-source-V1/.../tak/ArOverlayView.kt` (279 lines) is the reference. Roughly 70%
ports as-is: the drawing (`drawTarget`, `drawIconAt`, `drawEdgeArrow`), label/background
rendering, icon-cache, distance formatting, and the overall per-target loop shape.

**What does NOT port — all V5 API:** `KeyManager.listen` for gimbal attitude and camera source,
`km.getValue(KeyAircraftLocation3D)`, and the per-lens FOV table (M30T wide/zoom/IR — the Mini 2
has one fixed lens). Replace with `TakBridgeHolder.hud()` + the new `cameraPose()`.

**What ports but SHOULD BE FIXED — these are the substantive engineering decisions of 6D:**

### 1. Projection is linear; it should be perspective

V5 maps angle to pixels linearly:

```kotlin
val x = w / 2f + (dBearing / (hFov / 2.0)).toFloat() * (w / 2f)
```

That is a small-angle approximation. A real lens is a gnomonic (perspective) projection:

```kotlin
val x = cx + (halfW * tan(Δbearing) / tan(hFov / 2)).toFloat()
val y = cy - (halfH * tan(Δelev)    / tan(vFov / 2)).toFloat()
```

At the Mini 2's 73° horizontal FOV the linear form is visibly wrong toward the frame edges —
markers drift outward as they approach the border, and the error is zero at centre, which is
exactly the pattern that makes it easy to miss during a quick centre-of-frame check.
**Diagnostic signature: correct at centre, increasingly wrong toward the edges.**

### 2. It draws to full view bounds, ignoring the letterbox

V5 uses `width`/`height` directly. This tree's `FpvTextureView` letterboxes/pillarboxes the video
inside the view and publishes the actual video rectangle via `onVideoRectChanged` — that is why
`CrosshairView` stays aligned to the image. AR drawing to full-view bounds is offset by the size
of the bars, and the offset changes with video aspect. **Consume the same rect, and use its
centre and half-extents in the projection above, not the view's.**

### 3. Target elevation assumes everything sits at the takeoff plane

V5 deliberately treats contacts as ground targets:

```kotlin
// TAK PLI altitude (phone CoT) is unreliable / in a different reference than the drone's HAE —
// using it makes the dot float in the sky and slew as the gimbal tilts.
val elevDeg = Math.toDegrees(atan2(-dAlt, dist))
```

The judgment about PLI altitude is sound and should be kept — do **not** naively trust a
contact's reported altitude. But `-dAlt` assumes the contact is at the same elevation as our
takeoff point, which is wrong over any real terrain. **This tree has DTED, so it can do better:**

```
targetGroundMsl = DtedIndex.elevationAt(contact.lat, contact.lon)   // real terrain under target
Δz              = targetGroundMsl − aircraftMsl                      // aircraftMsl from TerrainAgl
elevDeg         = atan2(Δz, groundDistance)
```

Fall back to V5's flat-plane assumption when there's no DTED coverage at the target. Our own
dropped pins already carry a DTED-derived `alt`, so use it directly for those.

---

## Sub-phases

### A — Pose, projection, dropped pins only
The smallest thing that is self-validating.
- Add `TakBridgeHolder.cameraPose()` (see plumbing above).
- New `takpilot2/ArOverlayView.kt`: V4 telemetry, video-rect aware, **perspective** projection.
- Insert into `activity_takpilot2go_flight.xml` between the FPV and the crosshair, so the
  crosshair always draws on top (it's the aiming reference and must never be occluded).
- Render **only** `TakDropMarkers.listPins()` — no inbound contacts yet.
- **Exit:** drop a pin at the crosshair; it renders under the crosshair. Ground test, no flying.

### B2 — ADS-B air tracks — DEFERRED 2026-07-26 (operator), do after C
Air traffic arrives on the operator's `ADSB` TAK channel as `a-f-A-C-F` etc. It **already draws**
after B, but badly, and the four reasons are all design gaps rather than bugs:
- **Renders as a generic dot.** `TakMapMarkers.milMarkerRes()` requires `parts[2] == "G"`;
  air tracks are `A`, so they fall through to the PLI path — an airliner and a person on foot
  look identical, and with no `team` set both are default green.
- **The 5 km `MAX_RANGE_M` hides nearly all of it.** That cap was chosen for ground markers,
  where a distant marker is an unactionable speck. An aircraft at 1900 ft is plainly relevant at
  15 km and is exactly the traffic worth seeing.
- **A THIRD altitude datum.** ADS-B carries `altsrc: BARO` — pressure altitude, referenced to
  standard unless corrected. So the app would be mixing DTED MSL (own aircraft), WGS84 ellipsoid
  (ATAK contacts), and pressure altitude (ADS-B). ~60 m of altimeter error is only ~1.7° at
  2 km, so this is tolerable for "which aircraft is that" — but it is a third reference.
- **Clutter.** ~18 simultaneous tracks observed over Anchorage. Ground markers are sparse; air
  traffic is not.

Planned treatment: air tracks as their own CATEGORY, not a bolt-on — distinct aircraft glyph
(rotated to the CoT `course`), its own much larger range cap, altitude in the label since
vertical separation is what matters, and its own toggle in C's options menu.

*Incidental confirmation from the operator's CloudTAK screenshot:* `Mini2-SPI` is present on the
server, so our own sensor point does echo back as a contact. Without
`TakBridgeHolder.isOwnPublishedUid` it would sit pinned under the crosshair permanently, since
the SPI is by definition wherever the camera points. That filter is load-bearing, not defensive.

### B — Inbound TAK contacts
- Add `TakManager.takUsers`, skipping `TakMapMarkers.isHidden(uid)` so a marker hidden on the
  map is hidden in AR too (they are one picture; divergence would be confusing).
- Terrain-derived target elevation (fix 3 above).
- Team colours, 2525 frames for `a-{f,h,n,u}-G`, plain dot for PLI, grey when stale — via the
  now-shared `TakMapMarkers` helpers.
- **Exit:** a second TAK client's PLI renders on the person carrying it.

### C — Off-frame indicators, declutter, toggle + options
- Edge arrows for targets outside the frame (ports from V5 largely as-is).
- **Declutter + max-range filter.** A busy TAK picture will carpet the video otherwise; the 9
  live contacts already seen in this AO is enough to matter. Needs a range cap and probably a
  cap on simultaneous labels.

**Toolbar placement — DECIDED 2026-07-26 (operator).** Goes in the toolbar, not Pre-Flight;
it's an in-flight decision. There is room: the `<View>` between the RTH button and the drop-pin
button is `layout_weight="1"`, absorbing all the free space in the middle of the bar. Put the AR
button **immediately left of the drop-pin button**, at the start of the right-hand cluster —
drop-pin *creates* markers and AR *shows* them, so they read together. Tap = toggle,
**default OFF**.

**Long-press → AR options — DECIDED 2026-07-26 (operator).** Dialog with per-category
visibility toggles, matching the existing long-press idiom already used on the RTH button
(reset home) and the drop-pin button (markers list), and themed with `TakDialogTheme` like the
other marker dialogs.

**Three categories, not two.** The operator asked for "dropped markers vs other users' self
markers"; there is a third, and the split is free because `TakMapMarkers.milMarkerRes()` already
distinguishes it — `a-{f,h,n,u}-G` is a placed 2525 marker, `…-G-U-…` is a unit/PLI:

| Category | Source | CoT shape |
|---|---|---|
| **My dropped markers** | `TakDropMarkers.listPins()` | ours, 2525 |
| **Other users' markers** | `takUsers` where `milMarkerRes(type) != null` | inbound, 2525 |
| **Other users' positions (PLI)** | `takUsers` where `milMarkerRes(type) == null` | inbound, team-coloured dot |

Splitting inbound markers from inbound PLI is the distinction that actually matters in a busy
picture: a dozen people's position dots are what carpets the video, while their *placed* markers
are usually the thing worth seeing. Collapsing those two into one "other users" toggle would
force the pilot to lose both together.

Persist the three flags in SharedPreferences alongside the other flight-screen settings; default
all ON so the first-run behaviour matches what the toggle implies.

**Do not confuse this with the per-uid local hide.** `TakMapMarkers.isHidden(uid)` is an existing
per-marker dismissal shared with the mini-map, and AR must keep respecting it (sub-phase B). The
category flags here are AR-only and orthogonal — one says "not this specific marker, anywhere,"
the other says "not this whole class of thing, on the video." Both apply; a marker is drawn only
if it passes both.

- **Exit:** usable with a realistic TAK picture without obscuring the video, and the pilot can
  turn off other users' PLI without losing their own dropped markers.

### D — Calibration — BUILT 2026-07-27 (mechanism only, values not yet measured)
Shipped: adjustable 1x FOV in `TakBridgeHolder` (clamped `MIN_FOV`/`MAX_FOV`), persistence and
reset in `ArSettings.loadFov`/`saveFov`/`resetFov`, stepper controls in the AR options dialog.

**Still open:** the stored values are the published specs (73° × 45°); this airframe has never
been measured. And `BEARING_OFFSET_DEG` / `PITCH_OFFSET_DEG` remain 0 — the ~8° residual bearing
error noted above is the case for making them non-zero, but that trim was never built.

Keep `ArOverlayView.diag()` until both are settled; it is the instrument this needs.

---

## Test plan

### Ground tests (aircraft powered, NOT flying) — most bugs die here

1. **Self-test — the important one.** Drop a pin at the crosshair. It must render centred under
   the crosshair. Catches projection, pose, and video-rect errors in one shot, with no external
   reference.
2. **Gimbal sweep / FOV calibration.** Yaw the gimbal slowly across a stationary pin. It should
   track smoothly and leave the frame edge exactly as it passes FOV/2.
   **Exits the frame too early → configured FOV is too wide. Too late → too narrow.** Adjust
   `MINI2_HFOV` until exit coincides with the frame edge; repeat in pitch for `MINI2_VFOV`.
3. **Edge accuracy / projection check.** With a pin near the frame edge, compare its rendered
   position against the real object. Correct at centre but drifting outward at the edge means
   the projection is still linear somewhere.
4. **Walk-out test.** Someone stands 50–100 m away with ATAK/iTAK running. Their PLI should
   render on them. Exercises the whole inbound path end to end.

### Flight tests

5. Hover + gimbal sweep — confirm the ground results survive an airborne, moving airframe.
6. Fly past a marker — it must stay pinned to the world as the geometry changes, not slide.
7. **Marker on sloping ground** — validates the DTED elevation improvement specifically. Compare
   against a marker on flat ground at similar range.
8. **Fast yaw** — characterise (don't try to fix) how far AR lags the video. Telemetry and video
   have different latencies; markers WILL swim during rapid gimbal movement. Worth knowing the
   magnitude so it can be described in the field guide rather than reported as a bug.

### Diagnostic signatures — what a given error looks like

| Symptom | Most likely cause |
|---|---|
| Everything offset by a constant bearing | camera bearing model / `BEARING_OFFSET_DEG`, or the relYaw path failing over to raw yaw |
| Correct at centre, wrong toward edges | projection still linear, or FOV wrong |
| Correct horizontally, wrong vertically | `PITCH_OFFSET_DEG` or `MINI2_VFOV` |
| Correct near, wrong far | target elevation model (terrain lookup or flat-plane fallback) |
| Whole overlay shifted by a fixed number of pixels | video rect not being consumed (letterbox offset) |
| Markers swim during gimbal movement, settle when still | telemetry/video latency — expected, characterise it |

---

## Risks / gotchas

- **Bearing accuracy is the ceiling on everything.** The primary path is
  `aircraftHeading + gimbal.yawRelativeToAircraftHeading`; the fallback is
  `rawYaw + BEARING_OFFSET_DEG` where that constant is **0.0 and un-recalibrated for the Mini 2**
  (V5's 105.0 was tuned for the M30T). If the relYaw field ever goes null mid-flight the overlay
  will jump. Worth logging which path is in use.
- **Do not occlude the crosshair.** It is the aiming reference for marker drops; AR must render
  beneath it.
- **Declutter is not optional polish.** With a real TAK picture this can cover the video, which
  is a flight-safety-relevant regression, not an aesthetic one. The category filters in C are
  part of the answer, but they are a pilot action — the range cap and label cap still have to
  make the default state usable without anyone touching a menu.
- **Refresh rate.** V5 invalidates at 200 ms. This tree already has a 500 ms HUD tick; AR needs
  to be faster than that to avoid visible stepping, but every invalidate composites over live
  video — watch for FPV frame-rate impact, which is the one thing in this app that must not
  regress (see the main plan doc's video-pipeline history).
- **Contact altitude remains untrustworthy** regardless of the DTED improvement — the fix is
  about the *terrain* under the contact, not about believing the contact's reported height.

## Environment reminder

Build/install unchanged — see the main plan doc. Gradle 6.7.1 / JDK 11 / Kotlin 1.5.10 /
AGP 4.2.2, build from `Mobile-SDK-Android-4.18/Sample Code/`, Pixel 8 Pro over wireless adb.
