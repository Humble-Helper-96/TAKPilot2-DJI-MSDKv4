# TAKPilot2 → DJI Mobile SDK V4 Port Plan

*Reorganized 2026-07-22; fully updated again 2026-07-23 (end of a long field-testing session)
— current status up top, durable architecture reference next, condensed "how we got here"
archives at the bottom. If anything here looks stale, cross-check against the actual code —
this doc is a snapshot, not a live view. The project is now under **git** (see Environment),
so `git log` in the project dir is also a reliable changelog.*

## START HERE if resuming in a new chat

**Status: flyable, field-hardened, and useful today.** The Mini 2 connects and flies via
RC-N1 (Pixel 8 Pro primary test phone), streams live position/attitude/battery to a TAK
server as CoT with a **DTED-terrain-corrected Sensor Point of Interest**, records to the
aircraft's SD card, and returns home on command — through a custom flight screen with a real
instrument toolbar, locked-down mini-map with a home→drone line, auto-exposure, in-app debug
logging, and a pilot-facing Video Re-Sync failsafe. The FPV video pipeline went through a
multi-flight root-cause investigation this session (see Archive) and landed in a stable
place: smooth custom-MediaCodec decode, no periodic freezes, gradual static-scene
artifacting accepted as a known cost with a manual clear button.

**Phase 5 (RTSP video push) is BUILT and field-working (2026-07-24/25).** The Mini 2's live
video reaches a MediaMTX server as RTSP and plays cleanly in ATAK/CloudTAK, via **on-device
screen-capture transcoding** (MediaProjection → H.264 encode → RTSP push) with selectable
Low/Standard/High quality profiles. See the Phase 5 section for the full architecture and the
two hard bugs solved along the way. Phases 1–4 done and field-verified.

**Phase 6 (markers/pins + AR) is COMPLETE and field-confirmed (2026-07-27).** All four
sub-phases shipped: 6A inbound TAK contacts on the mini-map, 6B pin drops at the camera
crosshair, 6C marker management (move/rename/retype/re-send/delete/clear-all/reset-numbering),
and 6D the **AR overlay** — dropped pins, other users' markers and PLI, and ADS-B air tracks
projected onto the live FPV video and pinned to the world. Details in
`TAKPILOT2_PHASE6_PLAN.md` and `TAKPILOT2_PHASE6D_PLAN.md`.

**Also shipped in session 5 (2026-07-25 → 07-27), beyond Phase 6:**
- **Loss-of-signal → RTH failsafe.**
- **FAA UASFM ceilings on the HUD** — gridded 1/120° lookup, advisory only. *Caught in the
  field reading 4-year-old data from the wrong service layer; fixed, DB version bumped to wipe
  the stale import.*
- **Terrain-corrected AGL + MSL** on the HUD when DTED is available (`TerrainAgl`).
- **Flight timer / time-remaining** made accurate, and all units standardised to imperial
  (`Units.kt`).
- **Pilot Field Guide** (`FieldGuideActivity`) — three sections with live icon views.
- **ADS-B air tracks to 15 nm** and METAR ingest from a read-only TAK channel.
- **Gimbal-pitch HUD readout + crosshair accuracy cue** — ring goes green at ≤ −25°, amber
  −25° to −10°, white shallower, because ground-point error scales as `1/sin²(pitch)`.
- **HUD layout rebuilt** to stop the readout overlapping itself and to fit a Samsung S21.

**Read next:**
- **Current State** (below) — what's built, what's verified vs. pending, file-by-file.
  *Note: that section is a 2026-07-23 snapshot and predates everything listed above.*
- **Phase 5** — the RTSP-push implementation as built (screen-capture transcode), what's
  field-verified, and the remaining polish items.
- **Environment / Tooling** — git layout, toolchain, wireless-adb notes; needed every session.
- `git log --oneline` in the project dir is the most reliable changelog; these plan docs are
  snapshots and lag it.

### Open items — nothing is blocking, pick by interest

1. **FOV calibration values have never been measured.** 6D-D shipped the *tool* (adjustable
   1x H/V FOV, persisted, with reset) but `hFovBase`/`vFovBase` still sit at the published
   Mini 2 specs, 73° × 45°. An FOV error is invisible at frame centre and grows outward, so
   this only shows up as edge-of-frame AR drift. One ground test with a marker near the frame
   edge settles it.
2. **Residual SPoI bearing error of roughly 8°.** After the `CameraSlantPoint` terrain fix a
   drop lands where the crosshair points, but a bearing trim (`BEARING_OFFSET_DEG` /
   `PITCH_OFFSET_DEG`, both currently 0) was proposed and never built. This is the largest
   remaining accuracy item.
3. **ADS-B tracks lag reality by up to ~10 s** — the gateway's poll interval, not our bug.
   Dead-reckoning from the CoT `course` + `speed` would fix it, but `CotParser` discards both.
4. **Contact altitude source is still an open question.** `reported` (WGS84 `hae`) and
   `terrain` (DTED MSL) are both computed and logged every frame; the field picks the winner.
   See the 6D plan.
5. ~~**These plan docs are NOT under version control.**~~ **RESOLVED 2026-07-26.** They now
   live in `Sample Code/docs/` inside the git repo, so they version with the code they describe.
   Machine- and device-specific values (adb addresses, device serials, local paths) were split
   out into `docs/LOCAL_ENV.md`, which is **gitignored** — that is what lets these docs be handed
   to someone outside the project. Keep new operator-specific detail out of these four files and
   in `LOCAL_ENV.md`.

**To resume:** open a new chat, point it at this file and the project directory
(the repo root, `Mobile-SDK-Android-4.18/Sample Code/` — see `docs/LOCAL_ENV.md` for the
absolute path on this workstation), and say what you want to work on.
Project builds clean (Gradle 6.7.1 / JDK 11 / Kotlin 1.5.10 — see Environment).

**Standing constraint carried across sessions: do not commit without asking first.**

---

## Current State (as of 2026-07-23, end of session)

### Confirmed working on real hardware (multiple test flights, Pixel 8 Pro)
- Connect + fly the Mini 2 via RC-N1; TAK enroll/connect; live drone PLI + SPI on a second
  TAK client.
- Smooth, correct-aspect FPV video (custom MediaCodec pipeline — see Architecture + Archive;
  still the most fragile piece, re-test on hardware after ANY change near `FpvTextureView.kt`).
- **Video survives screen lock/unlock and Home↔Flight navigation** — recovers in ~3.5s via
  the hard-resync escalation (this was a hard-won in-flight fix, see Archive).
- **First-launch defaults** (Pre-Flight Setup): max altitude 200 ft, max distance 5280 ft,
  RTH altitude 150 ft, map style Street, callsign `sUAS`. User edits persist over defaults.
- **DTED elevation data**: ATAK-style zip import in Pre-Flight Setup §5 (16 Anchorage/Mat-Su
  tiles imported + verified), per-tile delete, and **terrain-corrected SPoI** — field-observed
  accuracy improvement, especially at shallow look angles.
- **Locked mini-map**: no pan/zoom/rotate (deliberate, operator spec), north-up, fixed zoom
  15, red home→aircraft line (#F44336) as the "which way back" reference.
- Auto-exposure (PROGRAM mode) with hidden **+1.0 EV bias** (raised from +1/3 after a dim
  field report), pilot EV slider ±2.0, live ISO/shutter readout.
- Record-to-SD, RTH with confirm/cancel, home-point set notice — all unchanged and solid.
- Toolbar (reorganized this session): hamburger menu (home) | RTH | TAK shield+dot | battery
  ring | GPS | RC signal ‖ Video Re-Sync | LIVE | REC. Connection/status icons grouped left,
  video controls right; LIVE and REC both read "knob left = off/paused".

### Built and installed, NOT yet field-validated (top of tomorrow's checklist)
- **FPV "Group A" loss-elimination** (hold-and-retry on codec stall, no-freeze overflow):
  the intended fix for static-scene artifacting build-up. **Needs the hover test** — hold a
  static hover 2–3 min, compare artifacting accumulation vs. before.
- **Video Re-Sync toolbar button**: renders correctly; underlying mechanism (force-unsync +
  `resetDecoder`) is field-proven 4/4 from the earlier navigation-recovery testing, but the
  button itself hasn't been tapped against real accumulated artifacting yet.
- **Debug Log screen** (AppLog viewer/export): built, compiles, AppLog file sink works;
  the screen's toggles/export/clear haven't had a focused field shakeout.

### Built 2026-07-26, NOT yet field-validated (Pre-Flight Menu + Home Screen overhaul)
- **TAK auto-connect + auto-pull-channels on app launch** (`tak/TakAutoConnect.kt`, called once
  from `TAKPilot2GoHomeActivity.onCreate`): if saved enrollment exists and the user hasn't
  logged out, connects silently and pulls "My Channels" ~1.5s later — Pre-Flight Setup should
  show a live connection and a populated channel list without the pilot tapping anything.
- **Flight-screen TAK icon is now a toggle** (`toolbarTakButton` in the flight toolbar) —
  taps `TakAutoConnect.toggle()`: connects from saved certs if down, disconnects if up.
- **My Channels is now a 3-column grid** (`TakChannelsStore.kt` + `TakConnectActivity.renderChannels`),
  left-to-right then down, evenly spaced (weight-1 cells, spacer-padded short rows).
- **Video streaming auto-stops on `onStop`** (leaving the flight screen to Home, or the app
  backgrounding/closing) — `TAKPilot2GoFlightActivity.onStop()` now calls
  `VideoStreamerHolder.stop()` if active. Start/Stop Video buttons removed from Pre-Flight
  §4 — the LIVE pill is the only start/stop control now; that section just edits/saves config.
  "Use TCP transport" moved below the video-quality radios, above the full-URL preview.
- **Map Display** (§2): Street/Hybrid/Custom are now a 3-column radio row, custom-URL field
  as its own row below.
- **DTED file list condensed** to a 2-column grid with a small "✕" delete instead of one
  full-width name+Delete-button row per tile — was the main cause of long scrolling with a
  full tile set (e.g. 16 Anc-Mat tiles).
- **Home screen STOP/QUIT button** (`homeQuit`): confirms, then tears down video/TAK/telemetry
  and calls `Process.killProcess(myPid())` — the "nuclear option" for a clean-slate relaunch
  after something gets stuck mid-op. Single-process app (no `android:process` services), so
  this kills everything including the DJI SDK's own threads.
- Installed to the Pixel 8 Pro 2026-07-26; **not yet flown/exercised** — needs a real pass:
  auto-connect on a cold launch, channel grid with a real multi-channel pull, TAK icon
  toggle round-trip, video stopping on Home navigation, and the Quit button's confirm+kill.

### Built 2026-07-24 evening: RTH long-press resets home point
Long-pressing the flight-screen RTH icon now resets the aircraft's home point to the phone's
current GPS fix (RC-N1 has no onboard GPS, so the phone is the only sensible reading of "the
controller's location"). Confirms first (shows the lat/lon it's about to set — this changes
where RTH sends the aircraft, unlike RTH-cancel which needed no confirmation), then calls
`FlightController.setHomeLocation(LocationCoordinate2D, callback)`. Uses last-known
GPS/network location from `LocationManager` (no new dependency — deliberately did NOT add
Play Services' FusedLocationProviderClient given this project's already-documented history of
duplicate-class fights with newer Play Services artifacts against its pinned 11.8.0 bundle).
**Not yet field-tested.**

### Built 2026-07-24 late evening: two small telemetry/CoT fixes
- **GPS sat-count readout** (`TAKPilot2GoFlightActivity.updateHud()`): used to show "—" whenever
  `hasFix` was false, indistinguishable from "no telemetry at all." Now always shows the real
  `hud.satCount` when telemetry exists; the icon's green/gray color still carries the fix/
  no-fix distinction. Lets a pilot watch the count climb while acquiring a lock instead of it
  just vanishing below threshold.
- **Drone CoT stale time** (`CotBuilder.DRONE_STALE_DURATION_MS`, taklite):
  15s → **2 minutes**. `DroneTakBridge.pushOnce()` already skips sending when lat/lon is
  invalid (GPS lost), so the *last* good CoT event's stale timer is what governs how long TAK
  keeps showing the aircraft at its last known position — 15s made it disappear almost as fast
  as the GPS hiccup itself. **Not yet field-tested.**

### Built 2026-07-24 late evening: zoom toggle + shoot-photo button
- **1x/2x digital zoom toggle** (`flightZoomButton`, boxed pill next to the shutter button):
  `Camera.setDigitalZoomFactor(1.0f|2.0f, callback)` — verified against the real (unobfuscated)
  `dji-sdk-provided-4.18.jar` API surface, not guessed; first guess (`setDigitalZoomScale`)
  was wrong and caught at compile time. Guarded on `isDigitalZoomSupported`.
- **Shutter button** (`flightShootPhotoButton`, `ic_camera_shutter.xml`): `onShootPhotoTapped()`
  in `TAKPilot2GoFlightActivity.kt` — `setFlatMode(PHOTO_SINGLE)` → `startShootPhoto()` →
  `setFlatMode(VIDEO_NORMAL)` to restore live FPV afterward. Saves to the aircraft's SD card
  (same target as video recording). First cut only — a later phase will drop a TAK marker with
  the captured image attached ("quickpic").

**BUG found 2026-07-24, FIXED 2026-07-25 (not yet field-verified):** taking a photo left the
camera stuck in a dark, fixed exposure afterward (~ISO 800 · 1/640, was ~ISO 100 · 1/30 before
the shutter tap) and the EV slider stopped having any effect. Confirmed theory: the
`PHOTO_SINGLE` → `VIDEO_NORMAL` flat-mode round-trip resets the camera's exposure mode off
`PROGRAM`, and the bare `setFlatMode(VIDEO_NORMAL)` `onShootPhotoTapped()` used to restore video
mode never re-forced it back. Fix: that restore step now calls
`ExposureController.applyDefaults(context, camera)` instead — the same call the aircraft's
initial connect uses, which does the `VIDEO_NORMAL` switch itself AND re-applies `PROGRAM` +
the biased EV in one go, so `onShootPhotoTapped()` no longer has its own separate (and
incomplete) mode-restore logic. **Needs a field flight + photo tap to confirm the feed comes
back at the right brightness and the EV slider works again afterward.**

### Built 2026-07-25: metering mode + EV bias retune + photo/video EV parity
- **Metering mode was never set at all** (found by inspection, not a bug report — checked both
  the app code and the real `dji-sdk-provided-4.18.jar` API directly). Now forced to
  `MeteringMode.CENTER` (center-weighted) for both video and photo, via
  `ExposureController.applyExposureSettings()` — a new function factored out of `applyDefaults`
  that bundles metering + `ExposureMode.PROGRAM` + the biased EV together, independent of which
  flat mode (video/photo) it's called from.
- **Hidden EV bias retuned**: `HIDDEN_BIAS_STEPS` 3 → 2 (was +1.0 EV, now +2/3 EV — steps are
  1/3-stop each). Slider still displays -2.0..+2.0 with 0.0 default; the bias is invisible to
  the pilot, applied only when talking to the camera.
- **Photo now gets the same total EV as video**: `onShootPhotoTapped()` calls
  `ExposureController.applyExposureSettings()` again right after switching to `PHOTO_SINGLE`
  (before `startShootPhoto()`) — PHOTO_SINGLE has its own separately-persisted exposure state,
  so without this a snapped still could come out at a different brightness than the live feed
  even after the earlier exposure-restore fix. **Not yet field-tested.**

### Built 2026-07-25: TAK log filter + flight-screen logging audit
- **Debug screen gained "Include TAK / CoT logs"** (third checkbox, default ON). Off = the
  `AppLog.TAK_TAGS` set (DroneTakBridge, TakManager, TakClient, CotParser, TakCertEnroller,
  TakGroupAssigner, TakMissionClient, TakMissionManager, TakAutoConnect, TakForegroundService)
  is dropped from the FILE sink so app-side logs aren't buried by the 2s CoT push. logcat still
  gets everything. Deliberately an explicit tag set, NOT a "starts with Tak" prefix test —
  `TAKPilot2GoHome` and `TakConnectActivity` are app-side screens that a prefix rule would
  wrongly eat. Unlisted tags fail OPEN (still logged), and FATAL crash traces are never
  filtered. **Add new TAK-subsystem tags to `TAK_TAGS` in `AppLog.kt`.**
- **Flight screen retagged**: `TP2Flight` for lifecycle + toolbar actions, `TP2Record` kept for
  camera capture (record/photo) only — `TP2Record` had been carrying unrelated lines like
  "tap: RTH", which made tag-grepping useless.
- **Logging gaps filled** (audited every flight-screen control): menu/back, RTH tap +
  confirm/cancel + already-going-home, RTH long-press home-point reset (incl. the phone fix's
  provider/age/accuracy, which is the thing to check if a reset ever lands somewhere wrong),
  zoom tap + `setDigitalZoomFactor` result, shutter tap + PHOTO_SINGLE switch + exposure
  restore, REC tap, LIVE stop/passthrough/permission-grant/deny paths, LIVE pill state
  transitions (edge-triggered so a long outage doesn't spam), EV slider (verbose tier — drags
  fire per-step), `onStop` stream auto-stop, `onDestroy`, and `FpvTextureView.requestResync`.
  `ScreenCaptureService` went from 1 log line to full lifecycle coverage (foreground start,
  projection acquired, stream status, refusal, teardown).
- **Not yet field-tested.**

### Not yet working / explicitly deferred
- **Phase 5 RTSP push is still a stub** (`DroneVideoStreamer.start()` reports "not
  implemented"); the LIVE toggle, config UI (Pre-Flight §4), and CoT `<__video>` advertise
  plumbing are all real and waiting. Full plan below.
- **Static-scene FPV artifacting** (gradual, clears on motion): accepted trade-off. The
  15s auto-resync was field-rejected (freeze = non-starter); every "ask the aircraft for a
  keyframe mid-stream" avenue was investigated and doesn't work on this SDK/aircraft (see
  Archive — don't re-litigate). Remaining levers if Group A isn't enough: frame-num loss
  detection, or the Phase 5 transcoder as the outbound answer.
- **SPoI residual error sources**: geoid/ellipsoid vertical-datum offset not corrected
  (DTED is MSL-referenced, DJI altitude is WGS84 — tens of meters possible, location-
  dependent); `BEARING_OFFSET_DEG` still inherited from M30T, un-recalibrated (mitigated by
  preferring `yawRelativeToAircraftHeading`).
- **RTH in-progress indicator** — still deferred (two-toned icon needs a real solution).
- **Data Sync screen QA pass** — still hasn't had focused attention.
- **Phase 6**: 6A/6B/6C all built and **field-confirmed 2026-07-26** (drop all 4 affiliations,
  move/rename/retype/re-send/delete, 14h stale + DTED elevation verified against raw CoT from
  CloudTAK, restart persistence). 6D (AR) still optional/deferred. **Phase 7 scoped down and
  effectively closed** — an audit against V5's `RcButtonManager` TakAction list found the
  toolbar/HUD work already covers everything applicable to the Mini 2 (drop markers, stream
  toggle, zoom; lens-switch and thermal-palette are M30T-only). The two genuine gaps (gimbal
  recenter, in-flight SPoI toggle) were reviewed with the pilot and explicitly declined.
- **Signal-loss failsafe — BUILT 2026-07-26, NOT field-verified.**
  `FlightLimitsController` now also pushes `setConnectionFailSafeBehavior` on connect
  (Return to Home / Hover / Land / leave-unchanged, default RTH), picker in Pre-Flight Setup
  §1, with a `getConnectionFailSafeBehavior` read-back logged as "aircraft signal-loss
  behavior is now: X". **Verify via that log line, not by flying out of range** — confirming
  the behavior for real means deliberately dropping the RC link mid-flight.
  *Not the same thing as the max-distance geofence*, which only stops the aircraft at the
  boundary and never returns; there is no SDK "RTH at the fence" setting and app-side distance
  monitoring was deliberately not built (see `FlightLimitsController`'s doc comment).
- **FAA UASFM altitude ceilings on the HUD — BUILT and FIELD-CONFIRMED 2026-07-26.** Pilot
  downloaded data for their area and the HUD correctly reported the airspace ceiling for their
  location. End-to-end that confirms: the paged ArcGIS download, Esri-JSON parsing, Room
  persistence, the off-main-thread index preload, **and the 1/120° grid math resolving to the
  right cell** — the assumption the whole design rests on.
  New `tak/UasfmDatabase.kt` (own `uasfm.db`, deliberately NOT extra tables in `terrain.db` —
  that DB uses `fallbackToDestructiveMigration()` and a version bump would wipe the pilot's
  imported DTED regions), `tak/UasfmStore.kt` (paged download + persistence),
  `tak/UasfmIndex.kt` (in-memory lookup, preloaded off-main-thread from
  `DJISampleApplication`). UI: Pre-Flight Setup **§6**, centre lat/lon + radius with
  "Use My Location" / "Check Size" / Download / Clear. HUD: new `fpvFaaCeiling` TextView on the
  flight screen, hidden entirely when nothing is downloaded.
  - **⚠ WRONG SOURCE LAYER SHIPPED FIRST — fixed 2026-07-26. Read this before touching the
    endpoint.** The original build pulled from `FAA_UAS_FacilityMap_Data_V5`, which reads like
    "version 5, therefore newest" but is a **stale snapshot** (it sits beside `_V5_Dev` and
    `_V5_AppTest`). It reported **0 ft in a real 200 ft grid** in Anchorage — caught by the
    operator standing in a known 200 ft cell in Anchorage and cross-checked against the FAA's own
    "Visualize it" viewer. Same cell, three layers:

    | Layer | CEILING | MAP_EFF |
    |---|---|---|
    | `FAA_UAS_FacilityMap_Data` ← **correct, now in use** | 200 | 7/9/2026 |
    | `FAA_UAS_FacilityMap_Data_Primary` | 0 | 1/26/2023 |
    | `FAA_UAS_FacilityMap_Data_V5` ← originally shipped | 0 | 10/6/2022 |

    The grid math and lookup were never wrong — the returned cell centre matched the computed
    cell exactly. Only the source was. **`MAP_EFF` is the tell**: it was visible as 10/6/2022 in
    the original research and went unquestioned. Any spot check returning a non-current
    effective date means the layer is wrong. `UasfmDatabase` version was bumped 1 → 2 purely to
    destroy the stale rows; pilots must re-download after updating.
  - **Source/API (all verified live against the service):**
    `FAA_UAS_FacilityMap_Data` FeatureServer layer 0 (`maxRecordCount` 2000), `f=json` +
    `returnGeometry=false&outFields=CEILING,LATITUDE,LONGITUDE,MAP_EFF` +
    `orderByFields=OBJECTID` (required — offset paging is incoherent without a stable sort) +
    `resultOffset`. `maxRecordCount`=1000. Response shape is
    `{"features":[{"attributes":{…}}],"exceededTransferLimit":bool}`. `MAP_EFF` is a
    `"M/d/yyyy"` **string**, not an epoch.
  - **The load-bearing finding: cells sit on a fixed 30 arc-second (1/120°) grid.** Lookup is
    `floor(lat*120)`, `floor(lon*120)` — no polygon stored, no point-in-polygon test. Verified
    against real cells in BOTH Anchorage and Fairbanks. Row/col are derived from the
    LATITUDE/LONGITUDE *centre* fields, never the polygon corners (corners carry ~1e-6° noise
    that can flip a boundary; a centre is half a cell from the nearest one). `UasfmStore`
    validates every feature lands near a cell centre and **skips + counts + surfaces** any that
    don't — a non-zero "off-grid skipped" count in the UI or log means the FAA changed the grid
    and this whole design needs revisiting.
  - **`CEILING` of 0 is a real, meaningful value** ("no ops without further coordination"), not
    a null — the parser tests `< 0`, not `<= 0`. Don't "fix" that.
  - Size: 370,441 features nationwide (refused — `MAX_CELLS` caps at 150k); an Alaska bbox is
    27,319 on the corrected layer. Duplicate (row,col) collisions keep the **lower** ceiling.
  - **Advisory display only** (operator decision) — nothing is pushed to the aircraft's flight
    limits. Three display states, deliberately distinct: a real cell (`FAA 200 ft`, red when
    exceeded) / inside the downloaded box but in no cell (`Class G · 400 ft`, grey — the Part
    107 default, never dressed up as a facility-map value) / outside the downloaded box
    (`FAA — no data here`, amber — we genuinely don't know, so don't imply 400).
  - **Remaining unverified display states** (all lower-risk than the confirmed path): the
    grey `Class G · 400 ft` no-cell state, the amber `FAA — no data here` outside-the-box
    state, and the red exceeded-ceiling colour. **Don't chase the red state by deliberately
    flying above a published ceiling** — that's a Part 107 problem, not a test. It's a single
    `>` comparison shared with the Class G branch, so it's adequately covered by inspection.
- **Pilot Field Guide — BUILT 2026-07-26, on-device verified (not yet read in the field).**
  New `takpilot2/FieldGuideActivity.kt` + `activity_field_guide.xml`, reached from a **Field
  Guide** button on the home screen under STOP/QUIT. Three sections: what the app is for, the
  Pre-Flight Setup screen (all six numbered sections), and the flight screen broken down one
  control at a time.
  - **Written for pilots, not developers** — no class names, no SDK talk. Limitations that
    affect a flight decision are stated plainly (local marker delete ≠ deleted for everyone,
    FAA layer is advisory, AGL vs ALT, geofence stops rather than returns).
  - **Icon examples are LIVE VIEWS, not screenshots.** Each is the real widget
    (`BatteryGaugeView`, `SignalBarsView`, `LiveToggleView`, `RecordToggleView`, the TAK badge
    + status dot, the RTH vectors) constructed and driven into the state being described, using
    the same tint constants the flight screen uses. They cannot go stale, because they *are*
    the icons.
  - Two layout traps hit and fixed, worth knowing before editing it: the content container needs
    `descendantFocusability="blocksDescendants"` or the ScrollView jumps past the top to the
    first focusable example widget; and each icon chip's width is computed from
    `paint.measureText(caption)` rather than left to `wrap_content` — the fixed-width icon
    otherwise wins the measurement and captions silently clip ("Not connected" → "Not conn").
  - **Keep it in sync.** It describes the HUD readout line by line and every toolbar control,
    so a UI change means a guide change. The MSL line below is the first case of that.
- **MSL altitude on the HUD — BUILT 2026-07-26, NOT field-verified.** New third height line
  under AGL/ALT. Computed as `takeoffTerrainElevMsl + heightAboveTakeoff` in
  `TerrainAgl.Reading.mslMeters`. DTED is already MSL-referenced so no datum conversion is
  involved. **Needs only the takeoff terrain reference**, not terrain under the aircraft — so
  it is available in strictly more situations than the AGL correction, and will legitimately
  show a number while the line above still reads ALT. Shows "—" until the takeoff reference
  latches.
- **HUD remaining-flight-time + imperial units — BUILT 2026-07-26; timer FIELD-CONFIRMED.**
  Field test (fresh battery, hover then manoeuvring): readout populated from `—` to 24 min once
  the aircraft had an estimate, elapsed timer counted up, and **remaining time tracked with
  load** as the pilot flew — which is the whole point of the change and confirms both the
  `GoHomeAssessment` plumbing and the null/non-positive "not reporting" handling. Imperial
  distance readouts not separately confirmed.
  - **Remaining time now comes from the AIRCRAFT**, via
    `FlightControllerState.goHomeAssessment.remainingFlightTime` (seconds) — it models real
    battery state and current draw. Replaces a battery-percent × nominal-31-min-endurance guess
    that ignored payload, wind, temperature and throttle, and so read optimistically high in
    exactly the conditions where the number matters. `NOMINAL_FULL_FLIGHT_SEC` deleted.
    Non-positive values are treated as "not reporting" and shown as `—` rather than
    "0 min left" (the aircraft returns 0 before it has an estimate, notably on the ground).
    *Also available on the same object if ever wanted, not currently surfaced:*
    `getTimeNeededToGoHome()`, `getBatteryPercentageNeededToGoHome()`,
    `getMaxRadiusAircraftCanFlyAndGoHome()`.
  - **Imperial standardised** via new `takpilot2/Units.kt`. Home distance was the only metric
    readout left (altitude was already ft, speed already MPH). `Units.feet()` for bounded
    values (aircraft distance — the geofence defaults to 5280 ft so it won't switch units
    mid-flight); `Units.distance()` for unbounded ones (dropped-marker range: feet under a
    mile, miles above). Conversion happens **only at display** — CoT, DTED and slant-range math
    stay in metres.
- **Terrain-corrected AGL — BUILT and FIELD-CONFIRMED 2026-07-26.** New `tak/TerrainAgl.kt`.
  Field test: took off to 100 ft above the takeoff point, flew to higher ground, and the AGL
  readout **decreased as terrain elevation rose** — the correct behaviour, and specifically it
  confirms the **sign** of the correction (a sign error is the classic failure here and would
  have read as AGL *increasing* over rising terrain, which looks plausible enough on a HUD to
  miss). Also implicitly confirms the takeoff reference latched and the under-aircraft DTED
  lookup works. *Still unverified:* absolute magnitude against a known reference, the `ALT`
  fallback label with no DTED coverage, and the latch surviving a mid-flight home-point move.
  DJI's `aircraftLocation.altitude` is height above the *takeoff point*; this converts it to
  height above the terrain **under the aircraft** using the imported DTED:
  `correctedAgl = takeoffRelativeAlt + (dtedElevAtTakeoff − dtedElevUnderAircraft)`.
  - **It's a difference of two DTED samples on purpose.** Both come from the same dataset in the
    same datum, so the MSL-vs-WGS84 geoid offset that's an open item for the SPoI **cancels
    out**. This is also why it deliberately does NOT use the SDK's
    `getTakeoffLocationAltitude()` — mixing an SDK "above sea level" figure of unverified datum
    with a DTED MSL sample would reintroduce exactly that problem.
  - **The takeoff terrain reference is latched once** from the first available home location
    (at which point home == takeoff), and never re-read. Load-bearing: long-press RTH lets the
    pilot **move the home point mid-flight**, but the aircraft's altitude stays referenced to
    where it actually launched — re-reading from a moved home would silently corrupt the
    correction by the terrain delta between the two. Reset per flight from
    `DroneTakBridge.start()`.
  - Terrain under the aircraft is re-sampled only after ~15 m of movement (each sample opens a
    file + 4 seeks, and this runs on the flight screen's main-thread HUD tick).
  - **HUD label moves with the correction:** `AGL` when DTED corrected it, `ALT` when it's the
    raw takeoff-relative figure. The FAA ceiling readout appends `~` when it's judging against
    an uncorrected altitude. Both the AGL readout and the FAA exceeded-check consume the same
    single `TerrainAgl.Reading` per tick so they can never disagree.
  - Residual error: DTED's own vertical accuracy, post-spacing smoothing, and any difference
    between true takeoff elevation and DTED's value there (rooftop/vehicle/riverbank launches
    bias the reference).
- **Pixel 10a** has a stale build (from the first install of 2026-07-23, before defaults/
  debug-logging/DTED/video fixes). Reinstall before using it as a test device.

### Architecture reference — where things live

**Flight screen** (`takpilot2/TAKPilot2GoFlightActivity.kt` +
`res/layout/activity_takpilot2go_flight.xml`):
- **Video**: `FpvTextureView.kt` — custom MediaCodec decoder (Annex-B NAL assembler from
  ~2KB non-aligned VideoFeeder chunks; bounded queue; newest-frame-only render; LOW_LATENCY
  + realtime priority). Loss-hardening: a NAL that can't be fed due to a momentary codec
  stall is **held and retried** (`pendingNal`), not dropped; queue overflow drops backlog
  but does NOT freeze/resync. Sync lifecycle: `waitForSync` until an SPS arrives; while
  unsynced, keyframe requests every 500ms escalating to a **hard resync**
  (`IdrRequesterHolder.forceResync` → `resetDecoder`) after 3s — this escalation is what
  makes lock/unlock and Home↔Flight recovery work. `requestResync()` = the pilot button:
  clears queue, forces unsync, fires the hard resync immediately. Video is left-pillarboxed
  (`applyAspect()` pivot at left edge) leaving the right strip for HUD.
- **`tak/IdrRequesterHolder.kt`** — process-wide dormant `DJICodecManager` used ONLY as the
  keyframe-request lever; created once, never destroyed (destroying/recreating it wedged
  DJI's native engine mid-flight — see Archive). Quirks (field-proven, don't rediscover):
  `resetKeyFrame()` reliably works **once per process**; `resetDecoder()` does nothing
  while the stream is healthy but reliably forces SPS/IDR when called while our decoder is
  genuinely unsynced.
- **Crosshair**: `CrosshairView.kt` — sibling overlay fed the video content rect.
- **Toolbar**: hamburger `ic_menu` (finish → home), RTH `ic_rth`/`ic_rth_home_set`, TAK
  shield + status dot, `BatteryGaugeView`, GPS icon+count, `SignalBarsView`, then right
  cluster: Video Re-Sync `ic_resync` (two-tone camera-in-arrows), `LiveToggleView` (stub
  until Phase 5), `RecordToggleView` (real). Both toggles: knob fixed LEFT, label right.
- **Mini-map** (160dp, MapLibre): `setAllGesturesEnabled(false)`, north-up, fixed zoom 15,
  recentered on the aircraft each 500ms HUD tick. Layers bottom-up: red home→aircraft
  `LineLayer` (gated on homeSet), cyan aircraft arrow, white home pin. Style pilot-selectable
  (`MaplibreStyle.kt` — Street default / Hybrid / Custom).
- **Exposure**: `tak/ExposureController.kt` — PROGRAM mode on connect, `HIDDEN_BIAS_STEPS=3`
  (+1.0 EV, invisible to the slider). Slider ±2.0; camera rejects >+3.0 total.
- **Flight limits**: `tak/FlightLimitsController.kt` — persisted-with-defaults (200/5280/150
  ft), applied one-shot per connect via `DroneTakBridge`.

**`tak/DroneTakBridge.kt`** — telemetry→CoT bridge; the single place all V4 component
callbacks are registered; `Hud` snapshot read by the flight screen every 500ms (check it
before assuming a telemetry field needs adding). Now also wires **`elevationLookup`**
(DTED) into both `CameraSlantPoint.compute()` call sites (SPI push + `lookPoint()`).

**DTED trio** (`tak/`): `DtedStore.kt` (import single tiles or ATAK-style zips — extracts
`.dt0/.dt1/.dt2`, flattens `w150/n61.dt2`→`w150_n61.dt2` to avoid cross-folder collisions;
delete; lives in `filesDir/dted/`), `DtedTile.kt` (UHL header parse + direct-seek bilinear
lookup, no full-file load — verified byte-for-byte against a real Anchorage `.dt2`),
`DtedIndex.kt` (lazy header cache, invalidated on import/delete). `CameraSlantPoint.compute`
now takes an optional elevation lambda: fixed-point iteration (≤4 rounds, 1m convergence)
adjusting effective AGL by terrain delta; falls back to flat-ground when no coverage.

**Debug logging** (ported from the Autel sibling per its dev-notes doc):
`com/taklite/util/AppLog.kt` (vendor-neutral facade — logcat always; when enabled also
`filesDir/logs/app.log` 1MB-rotated/2h-swept + `Downloads/TAKPilot2 Logs/` 10MB-capped
archive), `tak/DebugActivity.kt` + `activity_debug.xml` (toggles Standard/Detailed, live
tail, export via FileProvider `${applicationId}.fileprovider`, Clear/Delete), crash handler
chained in `DJISampleApplication.onCreate()`. All `Log.*` across tak/takpilot2/taklite
routed through AppLog. Home screen gained a **Debug Log** button.

**Pre-Flight Setup** (`tak/TakConnectActivity.kt` + `activity_tak_connect.xml`) — five
numbered sections now: 1 Drone Settings (defaults above, auto-save), 2 Map Display (Street
default), 3 TAK Server Connection (+ My Channels), 4 Video to TAK/MediaMTX (Phase 5 target —
UI complete and waiting), **5 Elevation Data (DTED)** (upload/list/delete).

---

## Environment / Tooling

**Git (new 2026-07-23):** the project dir `Mobile-SDK-Android-4.18/Sample Code/` is a git
repo, branch **`main`**, tag `baseline-current` at the pre-fork snapshot. A second worktree
`../SampleCode-option1-video/` holds branch **`option1-video`** — the DJICodecManager video
experiment, ended at a **"DEAD END" commit** (kept as the recorded negative result; do not
re-attempt, see Archive). `git log --oneline` on main is the changelog since the doc's
2026-07-22 snapshot.

**Toolchain:** Gradle 6.7.1 / JDK 11 / AGP 4.2.2 / **Kotlin 1.5.10** (matters for Phase 5
dependency choice — see plan). Build from `Mobile-SDK-Android-4.18/Sample Code/` (or the
worktree):
```
ANDROID_SDK_ROOT=<path-to>/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 ./gradlew :app:assembleDebug
```

**DJI API key is bound to the applicationId** (`com.anchortak.takpilot2gov4`), NOT the
manifest package. Changing applicationId (flavors, suffixes, side-by-side installs) breaks
aircraft registration unless a second key is registered in the DJI developer portal. A/B
testing therefore = build-and-swap from git branches, not parallel installs.

**Test devices:** Pixel 8 Pro (primary; wireless adb — port rotates every toggle/reboot:
`nmap -p- --open -T4 --min-rate 5000 <phone-ip>` then `adb connect`; home LAN
phone reachable by its LAN hostname at home, or over the field hotspot). Real hostnames,
SSIDs, IPs and device serials live in `docs/LOCAL_ENV.md`, which is gitignored.
Pixel 10a (USB, serial in `docs/LOCAL_ENV.md`) — has a stale early build.

**Hardware-connection staleness gotcha** (unchanged): after repeated force-stop/relaunch,
DJI's product-connection session can go stale — unplug the RC-N1 USB-C, wait ~10s, replug,
THEN force-stop and relaunch.

**DTED source data:** an ATAK-style DTED2 zip held outside this repo (16 tiles,
w148–w151 × n60–n61); a copy sits in the Pixel 8 Pro's Downloads.

---

## Open Items, in priority order

0. **Phase 6 field test** (top priority next flight session — see
   `TAKPILOT2_PHASE6_PLAN.md`'s "Field test checklist"): actually tap Drop and confirm the pin
   appears correctly on a second TAK client, the 14h stale, feed-scoped publish, restart
   persistence, and a real 2525 marker-frame render (only PLI dots seen live so far). Needs
   the pilot present since it broadcasts to the live operational picture.
1. **Phase 5 polish** (screen capture works — see Phase 5): Low/High/Standard profiles all
   field-validated 2026-07-26 (soak tests, no artifacting); **90-min thermal soak at 480p
   PASSED 2026-07-25** — slightly warm, no throttling (see Phase 5 section). **Found bug,
   needs a fix:** no clean reconnect path after a real network drop — see Phase 5 section
   "Known bug" below. Still open: permission flow on other devices. Bitrate trim (full-HUD
   screen runs a bit hot on CBR) explicitly deferred until the rest of the app is further
   along.
2. **Field-validate FPV Group A** — static hover 2–3 min; is artifacting build-up
   dramatically slower than before? Tap Video Re-Sync against real artifacting.
3. **SPoI polish** — geoid offset correction; `BEARING_OFFSET_DEG` recalibration for Mini 2.
4. **Phase 6 — markers, dropped pins, AR** (also unblocks crosshair-tap-to-drop-marker).
5. **Debug screen field shakeout**; Data Sync QA pass.
6. **RTH in-progress indicator** (deferred; needs non-tint solution).

---

## Phase Plan

```
Phase 0   Decisions — DONE
Phase 1   Hardware bring-up — DONE
Phase 2   Port SDK-agnostic core — DONE
Phase 3   Custom UI shell — DONE, substantially extended
Phase 4   Telemetry → CoT bridge — DONE, substantially extended
          (+ DTED terrain SPoI, debug logging, defaults, video-pipeline hardening — all
           landed under the Phase 3/4 umbrella this session)
Phase 5   RTSP video push — BUILT & field-working (on-device screen-capture transcode)
Phase 6   Map markers, dropped pins, AR overlay — 6A/6B/6C BUILT & FIELD-CONFIRMED
          6D (AR overlay) PLANNED, not started — see TAKPILOT2_PHASE6D_PLAN.md
Phase 7   RC-N1 hotkey UI — CLOSED (already covered by toolbar/HUD; remaining gaps declined)
Phase 8   Phone-screen density/QA pass — LARGELY DONE
Phase 9   Deferred: PSDK payload, speaker/megaphone — not needed yet
```

### Phase 5 — RTSP video push: AS BUILT (2026-07-24/25, field-working)

**Result:** Mini 2 live video → MediaMTX (RTSP) → ATAK/CloudTAK, playing cleanly. Delivered
via **on-device screen-capture transcoding**. Full detail is in `git log` (commits from
"Phase 5 Step 0" through "MediaProjection screen-capture streaming"); this is the summary.

**Protocol:** RTSP, TCP transport. (RTMP rejected — extra remux hop, higher latency, no
ecosystem fit. See git history / prior doc versions for the full rationale.)

**RTSP client — VENDORED SOURCE, not a Gradle dep.** `com.pedro.rtsp` (RootEncoder's rtsp
module, Apache-2.0, ~28 files under `app/src/main/java/com/pedro/rtsp/`, see its NOTICE.txt).
Why vendored: the library targets JDK-17 bytecode (its own build.gradle, every recent tag),
which this tree's AGP 4.2.2 D8/R8 categorically cannot dex — confirmed not a JitPack fluke.
Compiling the source with our own Java-8 target sidesteps the JDK wall permanently. One edit:
CommandsManager's User-Agent read the lib's generated BuildConfig (gone here) → static string.

**The video path, three modes** (`tak/DroneVideoStreamer.kt`, RTSP pusher; profile-driven):
- **Screen capture (the shipping path for all UI profiles).** `tak/ScreenCaptureEncoder.kt`:
  MediaProjection mirrors the WHOLE flight screen (FPV + HUD + map + toolbar, per operator
  spec) into a VirtualDisplay sized aspect-preserving to the profile, straight into an H.264
  encoder's input Surface. No second decoder (captures FPV's already-clean pixels), GPU
  scaling (no CPU downsample). Structurally immune to the decode-transcoder's artifacting.
  Encoder: CBR, 2s IDR (remote-viewer self-heal), `KEY_MAX_FPS_TO_ENCODER` caps fps. Hosted
  by `tak/ScreenCaptureService.kt` — a foreground service (type `mediaProjection`, MANDATORY
  on Android 14: startForeground FIRST, then getMediaProjection). Flight-screen LIVE toggle
  requests the one-time capture permission → onActivityResult starts the service.
- **Decode-transcode (fallback, kept in code, not in UI).** `tak/StreamTranscoder.kt`:
  decode source H.264 → downsample → re-encode. Works but the CPU downsample can't keep up on
  scene changes → NAL-queue overflow → reference-chain artifacting (only cleared by a manual
  resync). This is WHY we pivoted to screen capture. Reached only if a transcode profile is
  started WITHOUT a projection (e.g. Pre-Flight §4 "Start Video", which can't capture a useful
  screen anyway).
- **Passthrough ("original", code-only, removed from UI).** Raw aircraft NALs → RTSP, zero
  re-encode. Viewer-hostile: Mini 2 emits no periodic IDR (field-measured 112s keyframe gap),
  so ATAK joins black and can't recover without a manual resync. Kept only as a debug fallback.

**Quality profiles** (Pre-Flight §4 radio, persisted `video_profile`, default Standard):
Low 360p/10fps/275k · Standard 480p/15fps/550k · High 720p/15fps/1000k. Aspect always
preserved. All transcoded output publishes to a **`-Low`-suffixed path** (Feed-A → Feed-A-Low)
so the media server passes it through instead of re-transcoding — the suffix flows through
push/advertise/CoT/preview URLs via `VideoConfig.path()`.

**Two hard bugs solved (don't re-introduce):**
1. **RTSP packetizer never armed.** RootEncoder's `H264Packet.sendKeyFrame` is a one-shot that
   only latches on the first IDR it sees WHILE `RtspSender.running` is true — but `connect()`
   is async, so the bootstrap IDR was sent before `running` flipped and got discarded → every
   frame dropped forever ("waiting for keyframe"), feed shows "online" but carries no video,
   MediaMTX read-times-out at ~30s. Fix: request a fresh keyframe in `onConnectionSuccessRtsp`
   on EVERY connect (screen mode → encoder sync frame; passthrough → FPV resync). Also re-arms
   after any reconnect (RtspSender.stop resets the flag).
2. **Transcoder NAL-drop artifacting** (decode path only): fed the whole burst then drained →
   dropped NALs once the decoder's input buffers filled → same class as the FPV Group-A bug.
   Fixed with hold-and-retry + interleaved drain. (Moot now that screen capture is the path,
   but the fix stands in StreamTranscoder.)

**Keyframe-on-demand plumbing:** `IdrRequesterHolder.fpvResync` (set by FpvTextureView while
alive) is the reliable "make the aircraft emit an IDR" lever (resyncs the on-screen decoder;
direct DJICodecManager calls are ignored while it thinks it's healthy). Passthrough/decode
bootstrap uses it; screen capture doesn't need it (own encoder).

**LIVE pill:** VideoStreamerHolder wraps `onStatus` so every state change (incl. async
connect-success) refreshes the flight-screen `LiveToggleView` → RED track + ▶ PLAY icon when
actually streaming, gray + ⏸ when off. Field-confirmed.

**Field-verified 2026-07-25:** Standard screen-capture stream = full flight screen at
1068×480/15fps, publishing Feed-A-Low, clean through a busy moving scene (no artifacting),
LIVE pill red.

**Field-verified 2026-07-26:** Low profile (800×360/10fps, ~218kbps observed) — 5-min soak
starting 19:45:45, no artifacting, matches Standard's result. High profile also confirmed fine.
Bitrate tuning intentionally deferred (leave CBR overshoot alone for now). **Not yet
exercised:** the Android-14 permission flow on other devices.

**Thermal soak PASSED 2026-07-25: 90 minutes continuous at 480p (Standard profile)** on the
Pixel 8 Pro — phone only "slightly warm to the touch," no thermal throttling, no overload, no
stream degradation. This is the real answer to the long-soak open item (the earlier check was
far shorter). Screen-capture transcode + MediaProjection + H.264 encode running for 1.5h is
sustainable on this hardware; treat 480p/Standard as thermally safe for full-mission-length
flights. Longer/hotter-ambient soaks and the High profile at length are still unmeasured.

**Known bug (found 2026-07-26): no clean reconnect after a network drop.** Repro: streaming
live, disable Wi-Fi ~10s. RTSP connection dies, correctly detected — toast "streaming failed",
`LiveToggleView` flips to paused. MediaProjection/`ScreenCaptureService` correctly stays alive
underneath (separate concern from the RTSP push). Re-enable Wi-Fi, tap LIVE: because the
streamer's internal state never transitioned to a clean "stopped" state on failure, this tap
is interpreted as **stop** (toast "video stream stopped", projection torn down, status-bar
screen-recording indicator disappears) rather than "reconnect." A second LIVE tap is needed,
which re-requests capture permission and starts fully fresh. Net effect: a transient network
blip costs two taps + a re-grant instead of an automatic or one-tap recovery. Needs either
(a) RTSP auto-reconnect with backoff while keeping the projection alive, or (b) at minimum,
have the post-failure paused state map a LIVE tap to "reconnect the pusher" instead of "stop."
Likely touches `DroneVideoStreamer.kt`'s connection-state handling and `VideoStreamerHolder`'s
`onStatus` wrapper (see LIVE pill section above).

**Known minor items:** bitrate runs a bit over target on detailed full-screen content
(~760k observed at Standard — CBR, but the full HUD screen is busy); the recurring MediaMTX
"HLS segment duration changed … error in iOS clients" warning is benign for WebRTC/RTSP
viewers (HLS segments split on keyframes; only matters if an iOS HLS client is used).


### Phases 6–9

- **6**: port `TakMapMarkers`/`TakDropMarkers` onto MapLibre, `ArOverlayView` optional.
  **→ Full plan in `TAKPILOT2_PHASE6_PLAN.md` — its "Sub-phase status" section is the current
  source of truth, read that before continuing.** Status 2026-07-25 evening: **6A (inbound
  contacts) and 6B (drop pins at crosshair) are BUILT and installed** — new `tak/TakMapMarkers.kt`
  (GeoJsonSource + SymbolLayer, confirmed rendering 9 live contacts from the real TAK server)
  and `tak/TakDropMarkers.kt` (uid-stable send via the new `TakManager.sendMarkerWithUid`,
  14h marker stale, SPoI elevation on drops, toolbar button + dialog). The one open design
  question from the original plan (how pins get placed, given the locked mini-map) was
  answered — camera-crosshair placement via `lookPoint()` — and is now implemented. **What's
  NOT done:** the actual send-to-TAK path is install-verified but not field-tested (needs the
  pilot present, since it broadcasts to the live operational picture — see the Phase 6 plan's
  field-test checklist), and **6C (marker list panel: move/rename/retype/re-send/delete) has
  not been started.** 6D (AR overlay) still optional/deferred.
- **7**: on-screen triggers for the ~15 `TakAction` handlers; retire `RcButtonManager`'s
  hardware layer. **8**: final spacing/touch QA (Data Sync especially). **9**: only if needed.

---

## Visual Design Reference (source of truth for styling, extracted from TAKPilot2 V5)

Pulled originally from `TAKPilot2-DJI-source-V1/android-sdk-v5-sample/`; extended with the
tokens actually used in the shipped V4 toolbar/HUD work.

**Palette — dark theme throughout:**
| Role | Color |
|---|---|
| Screen background (home) | `#0E0E10` |
| Screen background (setup screens) | `#1A1A1A` |
| Card / panel background | `#1A1B1E` (translucent `#CC1A1B1E` over video) |
| Input field background | `#2A2A2A` |
| Primary text | `#FFFFFF` |
| Secondary text | `#AAAAAA` / `#DDDDDD`, subtitle tint `#9AC4FF` |
| Muted / label text | `#7A7A7A`, `#B0B0B0` |
| Hint text | `#666666` |
| Accent blue (buttons, toolbar, links) | `#1565C0`, toolbar/CTA blue `#0D47A1` |
| "Enter Flight" card gradient | `#0D47A1` → `#1B2740`, 135°, 1dp stroke `#2A4A7A` |
| Status green (connected, home-set, good signal) | `#4CAF50` |
| Status amber (caution) | `#FFB74D` |
| Status red (disconnected/low; home→drone map line) | `#F44336` |
| Live/recording red | `#E53935` |
| Aircraft self-marker / resync icon camera | cyan `#00E5FF` |
| TAK Setup / green action button | `#2E7D32` |
| Secondary buttons (Data Sync, Debug Log) | `#37474F` (slate) |
| Delete/danger buttons | `#B71C1C` |

**Shape language:** cards 14dp radius w/ subtle 1px border; primary CTAs 12dp; status dot
10dp filled circle. **Typography:** bold 24sp titles, 30sp letter-spaced CTA, 13–15sp body,
12sp uppercase letter-spaced eyebrows (`homeCardTitle`), 18sp bold values.

**Layout patterns:** home = logo/status + Quick Controls card (Pre-Flight Setup, Data Sync,
Debug Log) + gradient Enter Flight card. Setup screens = dark full-bleed, numbered sections,
label-above-field. Flight = solid `#0D47A1` toolbar, drop-shadowed no-box FPV text overlay,
translucent panels only over open video.

**Logo:** `takpilot2_logo.png` (400×400, shield/eagle), 100dp home / 84dp flight-card.

---

## Archive — resolved investigations (condensed; closed, don't re-litigate unless the symptom recurs)

**FPV video was sparse/laggy (2026-07-21) — SOLVED, locked in.** Mini 2 emits no SPS/PPS/IDR
in steady state — keyframes only on request. Fix: custom MediaCodec pipeline in
`FpvTextureView.kt` replacing DJICodecManager rendering (Annex-B reassembly, bounded queue,
newest-frame-only). Field-confirmed smooth, sub-second.

**16-bit requestCode crash on first launch (2026-07-23) — SOLVED.** `PERMISSION_REQUEST_CODE`
had been set to `20260723` (a date), exceeding Android's lower-16-bits limit for
`ActivityCompat.requestPermissions` → crash in `TAKPilot2GoHomeActivity.onCreate`. Now
`1001`. Keep all request codes ≤65535 (DTED picker uses 2001).

**In-flight FPV freeze on screen lock (2026-07-23) — SOLVED.** Locking the phone tore down
the flight Activity's surface; recreating the per-surface dormant DJICodecManager wedged
DJI's native engine (`Lightbridge: startStream videoCtlobjet == NULL`) — black video only a
force-stop could clear. Fix: `IdrRequesterHolder` process-wide singleton (create once,
never destroy) + the unsynced→3s→`resetDecoder` hard-resync escalation. Lock/unlock and
Home↔Flight recovery now field-proven (~3.5s).

**Keyframe-request truths (2026-07-23, field-measured — the load-bearing findings):**
`resetKeyFrame()` on the dormant manager works exactly **once per process**, then silently
no-ops. `resetDecoder()` is gated by DJI's own decode-health tracking: a no-op while the
stream is healthy (90s of 5s-interval calls produced zero IDRs), but reliably forces
SPS/IDR when invoked while genuinely unsynced (4/4). Root cause of both: the keyframe
authority lives in DJI's decoder, and ours — which detects the errors — isn't it. Every
"seamless mid-stream refresh" scheme is therefore dead on this stack; recovery requires
going through the unsync path (brief flash) or fixing it outbound via re-encode (Phase 5).

**Periodic self-resync (anti-artifacting timer) — REJECTED in the field.** 15s interval ×
0.6–3s freeze = non-starter for ISR. Removed; replaced by Group A loss-elimination (stop
manufacturing our own NAL loss: hold-and-retry on codec stall, no-freeze overflow) + the
manual Video Re-Sync button. Group A's hover validation still pending.

**Option 1 / DJICodecManager-as-decoder — DEAD END (2026-07-23, `option1-video` branch).**
Feeding raw VideoFeeder bytes to DJICodecManager on the Pixel 8 Pro: decoder+output-surface
recreation every ~3.3s (keyframe starvation → jitter); adding a 2s `resetKeyFrame` pump
stopped the resets but drove `Codec_OSAL_DequeueBuf` failures → total freeze. Ruled out our
aspect-transform as the cause. DJI Fly's smoothness is its proprietary stack, not
reproducible via MSDK VideoFeeder+DJICodecManager. The custom pipeline is the keeper.

**DJI key ↔ applicationId (2026-07-23).** Registration key validates against applicationId;
a `.opt1`-suffixed side-by-side build can't register. A/B = git build-and-swap.

**Video black screen after rig power-cycle (2026-07-22)** — never reproduced; attributed to
near-dead battery. **Compass yellow/red no-takeoff (2026-07-21)** — compass cal via DJI Fly.
**Mini 2 camera-mode gotchas (2026-07-22)** — boots in photo mode (switch to video first);
rejects legacy `setMode(RECORD_VIDEO)` → use `setFlatMode(VIDEO_NORMAL)` when supported;
shutter-priority doesn't drive live AE (PROGRAM does); EV comp rejected above ~+3.0.
**Landscape lock + home redesign (2026-07-21)** — app-wide landscape-only.

---

## Starting point (historical context)

- `TAKPilot2-DJI-source-V1/` — the real TAKPilot2 app on DJI MSDK **V5** (no Mini 2
  support). Reference for design + the V5 `DroneVideoStreamer`/RootEncoder usage.
- `Mobile-SDK-Android-4.18/Sample Code/` — stock DJI **V4** sample (supports Mini 2), now
  hosting all TAKPilot2 custom code, under git.
- Not a literal port: SDK-agnostic core reused unchanged (Phase 2); every DJI-touching
  piece rewritten against V4's callback APIs; substantial net-new UI from field testing.

## Sibling references

- **Autel port** (`<project-root>/Autel/AutelTAKPilot2/`) —
  same TAK/CoT core, different drone SDK. Already-harvested: `AppLog` debug facade (ported
  2026-07-23). Phase 5 porting sources: `AutelVideoStreamer.kt`, `LowBandwidthTranscoder.kt`
  + `TAKPilot2-LowBandwidthVideo-DevNotes.md` (see Phase 5 plan above).
