# TAKPilot2 — DJI MSDKv4 — rules for every coding session

**Written in Simplified Technical English (ASD-STE100).** This file goes to the agent on
every invocation. It holds the decisions and the safety rules that the code cannot show by
itself. The current state is `docs/TAKPILOT2_V4_PORT_SUMMARY.md`.

## What this application is

The TAK flight interface for the DJI Mini 2 with the RC-N1 controller and a Samsung Galaxy
S20 Ultra (approximately 360x800dp). The Autel EVO II 640T sibling application shares the
UI: a pilot must be able to change aircraft and find the same screens. The shared protocol
core is `com.taklite`, which both applications hold as the same code.

## Safety rules — these come from real incidents on the sibling

1. **Listener slots hold ONE client.** A second `set*Callback` replaces the first with no
   warning. Only `DroneTakBridge` and `DjiSdkBridge` own SDK callbacks. New consumers are
   FED from the bridge callback, not subscribed to the SDK.
2. **Do not detach the AirLink callbacks.** On the Autel port, removal detached the
   underlying packet subscription and re-registration did not re-attach it, which killed
   the RC signal indicator for the life of the process. Whether MSDK v4 shares the defect
   is unverified. The signal bars must never depend on a TAK toggle. See the comment in
   `DroneTakBridge.stop()`.
3. **Never write to the flight controller on a timer.** Limits go to the aircraft at connect
   and on an explicit button press only. Keystroke-burst writes crashed an aircraft on the
   sibling on 2026-08-02.
4. **Do not trust `onSuccess` from the camera alone.** Verify with a read-back where the
   result matters.
5. **Correct a sign ONE time, at ingest, never in consumers.** When one value has the wrong
   sign, examine the others immediately.
6. **`com.taklite.client.tak` must not import an SDK.** It is vendor-neutral by contract,
   and it is the same code in the Autel tree. A change here belongs in both trees.
7. **Test the hardware before you design around its limits.** Three wrong "the SDK cannot do
   this" calls on the sibling came from auditing one subsystem instead of the whole surface.
8. **`applicationId` is `com.anchortak.takpilot2gov4` and must not change** — the DJI API key
   is registered against this exact id. A suffix, flavor or side-by-side variant breaks
   aircraft registration outright.
9. **A completion callback can fire TWICE.** `Gimbal.setControllerMaxSpeed` invoked its
   callback twice per write on the Mini 2 (2026-08-12). Make completion handlers one-shot
   when a second call would repeat work.
10. **The Mini 2 refuses both battery-threshold writes**, and DJI's documentation is wrong —
   it lists `setSeriousLowBatteryWarningThreshold` as supported on the Mini 2. The aircraft
   holds warning 20% / land 10% and the app cannot change them. Trust the aircraft's answer,
   never the documentation. The read-back after Apply is what catches this class of refusal.

## Verification

- Unit tests: `./gradlew :app:testDebugUnitTest` — the pure-logic core. Run them before each
  commit that touches those files. Add a test when you change the policy they pin.
- The build: `./gradlew :app:assembleRelease`. Signing comes from `app/keystore.properties`
  and the SDK key from `app/dji-key.properties`, both kept out of git. Without them the
  build still runs and produces an unsigned APK, so a fresh clone builds fine.
- Toolchain is pinned: Gradle 6.7.1, AGP 4.2.2, **JDK 11**, Kotlin 1.5.10. JDK 17 does not
  work with this AGP. Room stays at 2.2.6; 2.4.x carries Kotlin 1.6 metadata this compiler
  cannot read.
- The device is a Samsung S20 Ultra on wireless adb. Do not let an unreachable device push
  work into "verify later".

## Conventions

- Documents are STE. New code comments are STE. Old comments become STE when a file is next
  touched for real work.
- UI state must show what the AIRCRAFT holds, not what was requested. Unknown is its own
  state (amber), never collapsed into off.
- Colours come from the tokens in `res/values/takpilot_colors.xml`. Do not add a new
  `Color.parseColor` call site. `res/values/colors.xml` belongs to the stock DJI sample —
  leave it alone.
- Layouts stay in the default dp bucket. No dp value from the Autel tree transfers; that
  controller is 1024x720dp and this phone is 360x800dp.
- Release notes are short and simple, one line per function, next to the APK.
- Do not commit without asking first.

## Current work

The Autel-parity pass is code-complete and **entirely unverified on hardware**. The plan and
its phases are in `~/.claude/plans/ok-i-have-my-atomic-castle.md`. The Autel tree is the
reference for each port and lives at `../../Autel/AutelTAKPilot2/takpilot-autel_v1-2`.

`versionName` stays at `1.1.0-dev` on purpose. It becomes `1.1.0` when something has flown.

What still needs a device, in rough order of consequence:

1. **The warnings banner.** It is what a pilot reads during a fault. The two functions mapping
   DJI enums to "unsafe" (`isAttitudeMode`, `isPoorGps`) cannot be unit-tested — those enums
   live only in the stub `dji-sdk-provided` jar — so they are verified by reading only.
2. **Contact retention.** Watch `contacts held: N total` on a live net near busy airspace for
   20 minutes. `total` must oscillate, not climb. This is the class of bug that OOM-killed the
   sibling in the air.
3. ~~**The operator marker**, on a second TAK client.~~ Done 2026-08-12: right position and
   `-Pilot` suffix confirmed on CloudTAK and TAKAware. The video url is on BOTH markers now,
   not the operator alone — an operator who wants the camera selects the aircraft. Both
   advertise one video uid, derived from the url, so a client sees one feed and not two.
4. **Every screen**, once. The colour migration touched all of them and a wrong token is
   invisible to the compiler.
5. **Pre-Flight locks** — a locked field must refuse the keyboard.
6. **Flight records** — a real track, and the orphan sweep after a kill.

Deferred rather than guessed: the map expansion, the WIDE/NEAR zoom levels and the smaller
symbol sizes were all tuned for a 1024x720 controller and need re-picking here; the resource
overlay row needs measuring; the in-flight quality picker needs a real downlink.
