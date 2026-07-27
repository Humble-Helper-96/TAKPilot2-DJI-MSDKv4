# TAKPilot2 Go V4 — Phase 6 Plan: Map Markers, Dropped Pins, AR

*Drafted 2026-07-25 at the end of the Phase 5 session, from a code audit of the V4 tree and
both reference implementations. Companion to `TAKPILOT2_V4_PORT_PLAN.md` — read that doc's
START HERE + Environment sections first; this one only covers Phase 6.*

---

## START HERE (new chat)

**Goal:** other operators' TAK contacts/markers render on the flight-screen map, and the pilot
can drop MIL-STD-2525 pins that publish to the TAK server.

**Exit criteria (from the main plan doc):** inbound contacts render; pins drop and send.

**Status as of 2026-07-27: PHASE 6 COMPLETE. 6A, 6B, 6C and 6D all BUILT and FIELD-CONFIRMED.**
Pilot flew and dropped markers of each affiliation, then used the 6C list panel to update them —
confirmed working well. **6D (AR overlay) was subsequently built out in full and has its own
doc, `TAKPILOT2_PHASE6D_PLAN.md`** — read that rather than the deferral note further down this
file, which is superseded.

Closed out since: 2525 marker-frame render was **dropped by operator decision** ("generic
markers for CoTs is more than enough for the mini map"), and marker management grew Clear All
and Reset Numbering.

**The single most important finding of the audit:** Phase 6 is a **map-rendering port, not a
networking port.** The entire TAK protocol side already exists and works in this tree (see
"What's already done" below). Nearly all the work is drawing things on MapLibre and building
the pilot UX around it. This held up completely through 6A/6B — no taklite networking surprises.

**Design is fully DECIDED (2026-07-25) — no open questions remain.** Map stays locked, pins
drop at the camera crosshair; see the "DECIDED" section, and "Answered" for the four detail
decisions (14h stale, local-only delete, echo dedupe, SPoI elevation). All four are now
implemented, not just decided.

**The update/move root cause is fixed.** `TakManager.sendMarkerWithUid(uid, ...)` is the real
send now; `sendMarker`/`sendMarkerToMission` are thin wrappers that mint a uid via the new
`TakManager.newMarkerUid()` and delegate. `TakDropMarkers.Pin` persists the CoT uid from its
first send and reuses it — the plumbing 6C's move/rename/re-send needs is already in place,
6C "only" has to build the list UI and wire it to `sendPin()`.

---

## Sub-phase status (2026-07-25 evening)

**6A — Inbound contacts — BUILT, field-confirmed with live traffic.** New
`tak/TakMapMarkers.kt`. Installed to the Pixel 8 Pro and connected to the real TAK server: 9
live contacts (real public-safety units and dispatch) rendered correctly with callsign labels and
team-colored dots, no crashes, `resync: 9 markers on map` in logs. Confirmed anchoring is
correct at normal zoom (a contact's dot sits exactly on its position next to the mini-map's
own aircraft/home layers). Layer order confirmed: inbound markers sit under aircraft/home.
**Not yet exercised:** the 2525 MIL-frame icon path (`makeMilIcon`) — all 9 live contacts seen
so far were plain `a-f-G-U-C` PLI, which takes the colored-dot path, not the marker-frame
path. Need an actual `a-{f,h,n,u}-G` marker from another client to see a framed icon render.
Also unexercised: stale/grey transition, and restart persistence of `savedMarkers`.

**6B — Drop pins at crosshair — BUILT and FIELD-CONFIRMED (2026-07-25 flight).** Pilot dropped
markers of each affiliation from the flight screen and confirmed they work correctly. New
`tak/TakDropMarkers.kt` + toolbar button + `dialog_drop_pin.xml`. Confirmed on-device:
- The `lookPoint() == null` guard correctly refuses the drop with a toast when GPS/gimbal
  aren't ready (tested with the aircraft disconnected).
- The dialog itself (type picker, editable pre-filled auto-name, lat/lon/elev readout) renders
  correctly — verified via a temporary hardcoded-lookPoint stub, then reverted; confirmed no
  `takpilot2_dropped.xml` prefs file was created after Cancel, so nothing leaked to the server.
- Icon: reworked per operator feedback into pin-in-crosshair (`ic_drop_pin.xml`) — see
  "Icon note" below.

**NOT YET DONE, because it requires transmitting to the live operational TAK server this
account is connected to, which the agent should not do without the pilot present:**
- Actually tapping **Drop** end-to-end: `sendMarkerWithUid` on the wire, the marker appearing
  correctly on a second TAK client, the 14h stale, feed-scoped publish via
  `TakMissionManager.publishUid`, and restart persistence of `TakDropMarkers`' own pins.
- This is the top of the list for the next real flight/field session — see "Field test
  checklist" below.

**Icon note:** `ic_drop_pin.xml` was redesigned once already per pilot feedback — a map pin
sitting inside a reticle that copies `CrosshairView`'s actual on-screen shape (gapped
arms + center ring), pin tip landing on the reticle center, so the composition itself explains
"marker drops where the camera is looking." Two small polish items flagged but not acted on:
the reticle is missing its top arm (occupied by the pin — reads fine at 24dp but is technically
asymmetric) and the cyan is slightly brighter than the resync icon's cyan. Worth a look on the
real device in daylight before touching further — screenshots flatter thin strokes.

**6C — Marker management — BUILT and FIELD-CONFIRMED (2026-07-25 flight).** Pilot updated
dropped markers via the list panel — confirmed working well. Long-press
the drop-pin toolbar button opens the "Dropped Markers" list (`TakDropMarkers.listPins()`,
range/bearing computed from `TakBridgeHolder.hud()` + `CameraSlantPoint`); tapping a row opens
the action menu (Move to crosshair / Rename / Change type / Re-send / Delete), all built on
`TakDropMarkers.moveToLookPoint/rename/changeType/resend/delete` — each of the first four
re-sends through the existing `sendPin()` with the pin's stored `cotUid`, so they move the
marker in place rather than duplicating it. Delete is local-only (A2) — no uid suppression, an
echoed-back marker is expected to reappear as ordinary inbound traffic.
Inbound local-hide is also wired: the mini-map now has an `addOnMapClickListener` that
hit-tests `TakMapMarkers.LAYER_ID` via `queryRenderedFeatures` and, on a hit, confirms before
calling the new `TakMapMarkers.hideInbound(uid)` (ports Autel's `hideInbound`, persists to the
existing hidden-uid set). Confirmed independent of the locked map's
`setAllGesturesEnabled(false)` per the risk note — build compiled clean and installed; the
click listener itself is otherwise unverified against a live TAK picture.
**Not yet exercised (needs the pilot + a live TAK server, same reason as 6B):** every row
action's actual wire effect (does Move really move the marker in place on a second client vs.
duplicate; does Re-send refresh time/stale; does inbound tap-to-hide correctly resolve the
tapped feature at the mini-map's small on-screen size) — add these to the Field test checklist
below before the next flight.

---

## What's already done (verified by audit, do NOT rebuild)

The `com/taklite/client/tak/` core in this tree is the same SDK-agnostic core the V5 and Autel
apps use, and it is **complete for Phase 6's needs**:

| Capability | Where | Status |
|---|---|---|
| Inbound CoT → parsed contact objects | `TakManager.onCotReceived()` → `CotParser.parse()` | works |
| Contact add/update/remove notifications | `TakManager.TakUserListener` (`addListener`) | works |
| Current known contacts | `TakManager.getTakUsers()`, `findUserByUid()` | works |
| Send a 2525 marker to TAK | `TakManager.sendMarker(lat, lon, alt, affiliation, name, "")` | works |
| Send/update a marker under a STABLE uid (built in 6B) | `TakManager.sendMarkerWithUid(uid, ...)` | works, install-verified, wire-untested |
| Send a marker scoped to a Data Sync feed | `TakManager.sendMarkerToMission(..., feed)` | works |
| Feed membership + publishing a uid to a feed | `TakMissionManager.joinedFeed`, `.publishUid()` | works |
| Contact model (uid/callsign/lat/lon/alt/team/type/stale) | `TakUser` | works |
| **DTED-corrected camera ground-intersection point** | `TakBridgeHolder.lookPoint()` → `CameraSlantPoint.compute()` | works |

`lookPoint()` is worth calling out: it already returns the terrain-corrected lat/lon the camera
crosshair is pointing at, using the DTED tiles. That is exactly what a "drop a pin on what I'm
looking at" feature needs, and neither reference app had it wired to pin-dropping.

## Reference implementations (and why neither is a drop-in)

Three renderers, three different map stacks — this is the third port, not a copy:

| App | Map stack | Files |
|---|---|---|
| TAKPilot2 V5 (original) | DJI uxsdk mapkit | `TAKPilot2-DJI-source-V1/.../tak/TakMapMarkers.kt` (398 ln), `TakDropMarkers.kt` (257 ln), `ArOverlayView.kt` (279 ln) |
| Autel sibling | osmdroid | `Autel/AutelTAKPilot2/takpilot-autel_v1-2/.../tak/TakMapMarkers.kt` (364 ln), `TakDropMarkers.kt` (219 ln) |
| **This app (V4)** | **MapLibre** | — to be written |

**Use the Autel version as the primary reference.** It's the cleaner of the two, shares this
tree's exact taklite core and `AppLog`, and its structural change (the map owner hands the map
in via `onMapReady` rather than a cross-module hook) is the same shape we want.

**What ports over unchanged (logic, no map API):** icon generation (`makeIcon`,
`makeMilIcon`, `teamColor`, `drawableToBitmap`), the `iconKeyFor` cache key scheme, 2525 type
parsing (`milMarkerRes` — the `a-{f,h,n,u}-G` vs `…-G-U-…` unit distinction), JSON persistence
of received markers + locally-hidden uids + dropped pins, the send/feed-scoped-send branching,
and the `Ui` callback interface that keeps dialogs in the Activity.

**What does NOT port — the real work.** osmdroid's model is `Marker` objects with individual
`setOnMarkerClickListener`s pushed into `map.overlays`. MapLibre has no such thing. The V4
equivalent is:
- one `GeoJsonSource` holding a `FeatureCollection` (one `Feature` per marker, properties
  carrying uid/callsign/icon-id), plus one `SymbolLayer` — **not** N marker objects;
- per-marker icons registered into the style via `style.addImage(id, bitmap)` (the generated
  bitmaps from `makeIcon`/`makeMilIcon` still work — they just get registered as style images
  keyed by the existing `iconKeyFor` cache key);
- hit-testing via `mapboxMap.addOnMapClickListener { queryRenderedFeatures(pixel, LAYER_ID) }`
  instead of per-marker click listeners.

The existing aircraft/home markers in `TAKPilot2GoFlightActivity.onCreate` are already exactly
this pattern (`GeoJsonSource` + `SymbolLayer` + `style.addImage`) — **copy that idiom.**

## Assets to bring over

Copy 4 vector drawables from
`Autel/AutelTAKPilot2/takpilot-autel_v1-2/app/src/main/res/drawable/`:
`marker_friendly.xml`, `marker_hostile.xml`, `marker_neutral.xml`, `marker_unknown.xml`.
Plain vectors, no dependencies, portable as-is.

---

## DECIDED 2026-07-25 — crosshair drop, map stays locked

The mini-map **stays locked exactly as it is** (160dp, no gestures, fixed zoom, north-up,
force-recentered). Opening it up for pan/zoom/interactivity is explicitly a *future* item, not
Phase 6. Pins are placed via the **camera crosshair** using
`TakBridgeHolder.lookPoint()` (DTED-terrain-corrected ground intersection).

### The drop flow

1. **Toolbar button** on the flight screen → drops a point at the crosshair.
2. **Prompt appears** with:
   - **CoT type picker**, four options (see table below).
   - **Marker name field**, pre-filled with the auto-name, editable.
3. **Accept** → marker is drawn locally and broadcast to TAK on the pilot's active channels.

### CoT types — already correct in this tree, no work needed

`CotBuilder.buildMarker()` already maps affiliation → the standard ATAK/iTAK/TAKAware
MIL-STD-2525 affiliation types. Verified against the existing switch statement; these are
exactly the four the operator specified:

| UI label | CoT type | ATAK rendering |
|---|---|---|
| Friendly | `a-f-G` | blue rectangle |
| Hostile  | `a-h-G` | red diamond |
| Neutral  | `a-n-G` | green square |
| Unknown  | `a-u-G` | yellow clover / quatrefoil |

The 4 vector drawables to copy from the Autel tree are the in-app equivalents of these shapes.

### Channel broadcast — already handled, no work needed

`TakManager.sendCot()` already injects `<marti><dest group="X" send="true"/></marti>` for every
selected channel, merging into an existing `<marti>` if one is present. Any marker sent through
`sendMarker`/`sendMarkerToMission` is automatically scoped to the pilot's active channels. The
"broadcast to active channels" requirement costs zero new code.

### Auto-naming

Default name = **drone callsign + `-P<n>`**, incrementing: `sUAS-P1`, `sUAS-P2`, …
- Counter is a single persisted integer (survives restarts) so numbers never collide.
- The counter is only consumed when the auto-name is actually **accepted unchanged** — the
  dialog previews the next number, and editing the field to a custom name leaves the counter
  untouched (so a custom-named pin doesn't burn `-P3` and leave a gap).
- If the pilot edits the field at all, the name is used verbatim with **no suffix applied**.

---

## Field test checklist (top of next flight session)

None of this can be safely exercised by an agent alone — it means transmitting to the live
operational TAK server. Run through this with the pilot present:

1. ~~**Drop a pin** from the flight screen toolbar button, all 4 affiliations at least once.
   Confirm it appears correctly (shape/color/name) on a second TAK client (ATAK/CloudTAK).~~
   **DONE 2026-07-26** — pilot dropped all 4 affiliations in flight; confirmed working.
2. ~~**Re-send / move (6C is now built)**~~ — **DONE 2026-07-26** — pilot exercised the
   "Dropped Markers" list panel's update actions in flight; confirmed working well. (Delete /
   inbound-hide specifically not called out by the pilot — worth a quick confirm next flight.)
3. **Feed-scoped send** — join a Data Sync feed, drop a pin, confirm it's scoped (not
   server-wide) and that `TakMissionManager.publishUid` registers it in the feed's contents.
   Still open — the CloudTAK sample below was a plain broadcast, not feed-scoped.
4. ~~**14h stale**~~ — **CONFIRMED 2026-07-26** via raw CoT pulled from CloudTAK: `start`
   01:06:06.028Z, `stale` 15:06:06.028Z — exactly 14h. See sample below.
5. **Restart persistence** — drop a pin, force-stop/relaunch the app, confirm the pin
   redraws from `TakDropMarkers`' saved JSON without re-sending.
6. **2525 marker-frame render** — get another client to drop/broadcast an `a-{f,h,n,u}-G`
   marker (not PLI) and confirm `TakMapMarkers.makeMilIcon`'s framed icon renders correctly
   on this app's mini-map (only the plain-dot PLI path has been seen live so far).
7. **Icon polish** — look at `ic_drop_pin.xml` on the actual device in daylight; decide if the
   missing top reticle arm / cyan brightness need adjusting (see Icon note above).

**Sample confirmed CoT (dropped Hostile marker, pulled from CloudTAK 2026-07-26):**
```json
{
  "properties": {
    "callsign": "Mini2-P3", "type": "a-h-G",
    "time": "2026-07-26T01:06:03Z", "start": "2026-07-26T01:06:06.028Z",
    "stale": "2026-07-26T15:06:06.028Z", "remarks": "Dropped by <operator-callsign>",
    "precisionlocation": { "geopointsrc": "Human", "altsrc": "DTED0" }
  },
  "geometry": { "coordinates": [<lon>, <lat>, 176.65605890327478] }
}
```
Confirms: 14h stale (item 4 above), correct `a-h-G` type mapping, auto-naming
(`Mini2-P3` — real drone callsign, not the `sUAS` default), and A4's DTED-sourced elevation
(`altsrc: "DTED0"`, not the old hardcoded 0.0) — MSL-referenced per the known geoid/ellipsoid
caveat, not a regression.

---

## The update/move problem — root cause and fix — RESOLVED in 6B

The operator asked how to move + rebroadcast an existing marker. The audit found the exact
blocker, and it's now fixed (see "Sub-phase status" above for the implementation; kept below
for the original reasoning):

**Root cause:** `TakManager.sendMarker()` mints a **brand-new random uid on every call**
(`"marker-" + UUID.randomUUID()…`). In CoT, **the uid *is* the marker's identity** — so
re-sending a moved marker today would spawn a *second* marker on every other TAK client instead
of moving the original. This is why "update" doesn't work, not anything about the map.

**Fix — uid stability.** Moving/updating a marker in TAK is just: re-send a CoT event with
**the same uid**, new `lat/lon`, and fresh `time`/`start`/`stale`. Every ATAK/iTAK/TAKAware
client will move the existing marker in place. That means:

1. Add an overload that accepts a caller-supplied uid instead of generating one, e.g.
   `TakManager.sendMarkerWithUid(uid, lat, lon, alt, affiliation, name, remarks[, mission])`
   — the existing `sendMarker` becomes a thin wrapper that generates a uid and delegates.
   (`CotBuilder.buildMarker` already takes `markerUid` as a parameter, so **no CoT-builder
   change is needed** — the uid is generated one layer too high, that's all.)
2. `TakDropMarkers.Pin` must **persist the CoT uid** assigned on first send, alongside its
   local key, and reuse it for every subsequent update.
3. Any edit (move / rename / affiliation change) re-sends with that stored uid.

**Move UX (given the locked map):** re-aim the aircraft camera at the new spot, open the marker,
tap **"Move to crosshair"** → takes a fresh `lookPoint()` and re-sends with the stored uid.
Consistent with the same crosshair paradigm as dropping.

### Selecting an existing marker — recommend a list, not the map

With the map locked and only 160dp, tapping a specific marker is unreliable (and multiple pins
overlap at that zoom). Recommend a **Markers list panel** instead — opened from a toolbar
button or a long-press on the drop button:

- One row per dropped pin: affiliation icon, name, and (useful in flight) range/bearing from
  the aircraft.
- Row actions: **Move to crosshair**, **Rename**, **Change type**, **Re-send**, **Delete**.

This needs no map interaction at all, works while the map stays locked, and gives rename/retype
a natural home. `queryRenderedFeatures` hit-testing on the mini-map can be added later as a
convenience if the list proves insufficient — it is *not* required for this design.

---

## Answered 2026-07-25 (Q1–Q4 all decided, all IMPLEMENTED in 6B)

**A1 — Marker stale time: 14 hours.** Change `buildMarker`'s `stale = now + 600000` (10 min) to
14h. Rationale (operator): long enough to persist through most incidents, short enough that
markers clear themselves before the next shift — no manual cleanup burden between shifts.
*Note:* the 10-minute value is also used by nothing else in `buildMarker`, so this is a
one-constant change scoped to dropped markers. Do **not** touch `DRONE_STALE_DURATION_MS`
(2 min, live drone track) or the SPoI stale (15s) — different lifetimes on purpose.

**A2 — Delete is LOCAL ONLY.** This is TAK's default behavior and matches operator
expectation. **No `buildDelete`/`t-x-d-d` work is needed** — dropping that from scope.
Propagating deletes is a Data Sync concern and is explicitly out of scope for now.
*Accepted consequence:* a locally-deleted marker keeps showing on other clients until its 14h
stale expires. That's understood and intended, not a bug to chase.

**Deleting does NOT suppress the uid** (operator decision, 2026-07-25). Delete simply removes
the pin from `TakDropMarkers` and from our map. If the server or another client subsequently
echoes/re-broadcasts that marker, **it is expected to reappear** — as a normal inbound marker
owned by the wider TAK picture, not by us. The shared picture stays the source of truth; a
local delete only says "clear it off my map right now," it does not assert the marker is gone.
Do **not** add deleted uids to a suppression/hidden set.
*(If the pilot wants a reappeared marker gone again, that's the ordinary inbound-marker
local-hide path in 6C — a separate, deliberate action.)*

**A3 — Dedupe echoes: yes, for markers we currently own.** `TakMapMarkers.upsert()` skips any
uid that `TakDropMarkers` is *currently* holding, so a live pin of ours is never drawn twice.
Once we delete a pin we no longer own that uid, so the dedupe no longer applies to it and it
can legitimately return through the normal inbound path — which is the intended behavior above.

**A4 — Markers are placed at the SPoI-computed elevation.** Requires a small change to
`CameraSlantPoint`: `GroundPoint(lat, lon, rangeMeters)` currently does **not** carry the
elevation — the terrain-iteration loop computes `targetElev` internally and then discards it.
Add an `elevationMeters` field to `GroundPoint`, populate it from the converged `targetElev`
(and from the flat-ground fallback when there's no DTED coverage), then have
`TakBridgeHolder.lookPoint()` return that instead of the hardcoded `0.0`.
Low-risk: adding a field with a sensible default won't disturb the existing SPI push path that
shares `compute()`.

> **Known inherited caveat (not new, don't be surprised by it):** DTED elevations are
> **MSL-referenced** while CoT `hae` wants WGS84 ellipsoid height. The main plan doc already
> tracks this uncorrected geoid/ellipsoid offset as an open SPoI item. Marker altitude will
> carry the same offset as the existing SPoI does — it is *not* a regression introduced here,
> and fixing it properly is the separate geoid-correction task already on the backlog.

---

## Proposed sub-phases

Ordered to front-load value and defer the contentious UX. Each is independently shippable and
field-testable. **6A and 6B below are BUILT — see "Sub-phase status" at the top of this doc for
current state; the bullet lists here are kept as the original spec/reference, not a to-do
list.** 6C is the active to-do.

### 6A — Inbound contacts on the mini-map (read-only) — BUILT
No UX decisions needed; start here regardless of the decision above.
- New `tak/TakMapMarkers.kt`: port the Autel object, swapping osmdroid `Marker`/`overlays` for
  a `GeoJsonSource` + `SymbolLayer` + `style.addImage` (copy the aircraft-marker idiom already
  in the flight activity).
- Register the `TakUserListener` at app start (like Autel's `install(context)`) so contacts
  accumulate before the flight screen opens; `onMapReady`/`onMapDestroyed` hooks from
  `TAKPilot2GoFlightActivity`.
- Port icon generation + the icon-key cache verbatim.
- Port persistence of received 2525 markers and locally-hidden uids.
- Layer ordering: inbound markers must render **under** the aircraft arrow and home pin.
- **Exit:** another ATAK/CloudTAK client's PLI and dropped markers appear on the mini-map with
  correct team colors, 2525 frames, callsign labels, and stale (grey) handling.

### 6B — Drop pins at the crosshair — BUILT (send path field-untested, see checklist above)
Depends on 6A's icon/rendering machinery.
- **taklite changes first:**
  - add `TakManager.sendMarkerWithUid(...)` (uid-stable send); make the existing `sendMarker` a
    wrapper that generates a uid and delegates. `buildMarker` already takes a `markerUid`
    parameter, so no signature change there.
  - `CotBuilder.buildMarker`: dropped-marker stale 10 min → **14 hours** (A1).
- **Elevation (A4):** add `elevationMeters` to `CameraSlantPoint.GroundPoint`, populate from the
  converged terrain elevation (and the flat fallback), and return it from `lookPoint()` in place
  of the hardcoded `0.0`.
- New `tak/TakDropMarkers.kt`: affiliation enum (the 4 types above), `Pin` model **including
  the persisted CoT uid**, JSON persistence, `sendPin` (incl. feed-scoped
  `sendMarkerToMission` + `publishUid` branch), `Ui` callback interface for dialogs.
- Persisted auto-name counter + the "custom name doesn't consume a number" rule.
- Toolbar drop button → dialog: 4-way affiliation picker + editable pre-filled name field →
  Accept places at `lookPoint()`, draws locally, and broadcasts on active channels.
- Guard: if `lookPoint()` returns null (no GPS/gimbal yet), refuse with a clear message rather
  than dropping at a garbage location.
- **Exit:** pilot drops a pin, it appears on the mini-map, shows up on a second TAK client with
  the right shape/color/name, and survives an app restart.

### 6C — Marker management (move / rename / retype / re-send / delete) — BUILT, wire-untested
This is where the operator's "update an existing marker" requirement lands. The uid-stable
`sendMarkerWithUid` + `Pin.cotUid` persistence it depends on already exist (built in 6B) — this
sub-phase is new UI (the list panel + inbound local-hide) wired to `TakDropMarkers.sendPin()`
and `TakMapMarkers`' hidden-uid mechanism, not new taklite/CoT work.
- **Markers list panel** (toolbar button / long-press the drop button): one row per dropped pin
  — affiliation icon, name, range+bearing from the aircraft.
- Row actions: **Move to crosshair** (fresh `lookPoint()`, re-send with the stored uid),
  **Rename**, **Change type**, **Re-send**, **Delete**.
- **Delete = local only** (A2): removes the pin from `TakDropMarkers` and our map, sends nothing.
  No `buildDelete`/`t-x-d-d` work. Deleted uids are **not** suppressed — if the marker is echoed
  back it reappears as an inbound marker, by design.
- Inbound markers: tap → local-hide (this map only, stays on server) — port Autel's
  `hideInbound` + hidden-uid persistence. This is the path for dismissing a marker that came
  back after deletion, or anyone else's marker.
- **Exit:** a dropped marker can be moved and the change is reflected **in place** on a second
  TAK client (not duplicated); rename/retype/delete all round-trip correctly.

### 6D — AR overlay — BUILT 2026-07-26/27. **SUPERSEDED — see `TAKPILOT2_PHASE6D_PLAN.md`.**

*The deferral recommendation below is kept for the record and is no longer the plan.* The
operator asked for AR directly, a code audit found the deferral had overstated the risk — the
camera-pose model AR needs was already in this tree and field-proven by the crosshair marker
drop, so the centre of frame was correct by construction and only the off-axis projection was
new — and A/B/C/B2/D all shipped and were confirmed in flight.

> ~~`ArOverlayView.kt` exists only in the V5 source and is written against V5's `KeyManager`/
> `KeyTools` key-value API, which does not exist in V4 — the telemetry plumbing would need a full
> rewrite against V4 callbacks (though `DroneTakBridge` already caches everything it needs:
> aircraft location, heading, gimbal attitude). Its own header calls it "PROTOTYPE / experimental
> … good enough to 'find someone,' not survey grade." Suggest deferring past Phase 6 unless
> there's a specific operational pull for it.~~

---

## Risks / gotchas

- **Icon churn vs. `style.addImage`.** The Autel icon cache is keyed on
  `team|stale|drone|milRes|callsign` — callsign is in the key, so every distinct contact makes
  its own bitmap. Registering hundreds of style images could get heavy with a large TAK picture.
  Watch it; consider capping or evicting if a busy server shows a problem. Not a day-one worry
  with a handful of contacts.
- **Stale contacts.** `TakUser.isStale()` flips the icon grey — that's a *rendering* change on
  an existing feature, so the source needs updating on a timer, not only on inbound CoT. The
  reference relies on updates arriving; a periodic re-render tick may be needed. (The flight
  screen already has a 500ms HUD tick to piggyback on.)
- **Thread safety.** `TakUserListener` fires on the TAK client's socket thread. All MapLibre
  style/source mutation must be marshalled to the main thread. Autel's osmdroid code was
  looser about this than MapLibre will tolerate.
- **Map lifecycle.** `onMapDestroyed` must drop all cached `Feature`/image references — the
  existing code already learned this lesson with the aircraft/home layers.
- **Don't regress the locked mini-map.** No gesture re-enabling as a side effect of adding a
  click listener; `addOnMapClickListener` works independently of `setAllGesturesEnabled(false)`
  — verify that's actually true on MapLibre 9.6.0 early, it's load-bearing for 6C under Option A.
- **Logging.** Use `AppLog` with new tags, and add them to `AppLog.TAK_TAGS` if they're
  TAK-subsystem chatter (the Debug screen's "Include TAK / CoT logs" filter reads that set).

## Environment reminder

Build/install unchanged — see the main plan doc's Environment section. Gradle 6.7.1 / JDK 11 /
Kotlin 1.5.10 / AGP 4.2.2, MapLibre 9.6.0, build from `Mobile-SDK-Android-4.18/Sample Code/`,
Pixel 8 Pro over wireless adb (port rotates — `nmap` then `adb connect`).
