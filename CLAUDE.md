# TAKPilot2 — DJI MSDKv4 — rules for every coding session

**Written in Simplified Technical English (ASD-STE100).** This file goes to the agent on
every invocation. It holds the decisions and the safety rules that the code cannot show by
itself. The current state is `docs/TAKPILOT2_V4_PORT_SUMMARY.md`.

## What this application is

The TAK flight interface for the DJI Mini 2 with the RC-N1 controller and a Samsung Galaxy
S20 Ultra (about 914x411dp landscape — every screen is landscape). It is one of three
TAKPilot2 applications, with the Autel EVO II 640T and the DJI MSDKv5 siblings:

> A pilot changes airframe and finds the same screens, the same controls in the same places,
> and the same words.

The shared protocol core is `com.taklite`, which all of them hold as the same code.

**This is the only one of the three that runs on a phone.** The Autel and MSDKv5 siblings run
on smart controllers, so their dimensions are larger — specification §7.

## The UI specification

`../../../../TAKPILOT2-UI-SPEC.md` is the single source of truth for the user interface of
all three applications. It outranks any UI note in this file or in `docs/`. Read it before
you change a screen, a layout, a colour or a readout format. This tree's gap list is in
`../../../../TAKPILOT2-UI-CONFORMANCE.md`.

A UI change lands in all three applications, or it lands in none.

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
- One layout file per screen. All size variation goes through `values-*/dimens.xml`, never a
  `layout-land` or a `layout-sw*` file. This tree uses the base bucket and `values-h440dp`.
  No dp value transfers between trees: specification §7.
- Release notes are short and simple, one line per function, next to the APK.
- Do not commit without asking first.

## Current work

**v1.2.1 IS RELEASED — tag `v1.2.1`, versionCode 7, 2026-08-18 — AND IT HAS NEVER FLOWN.**
It builds, it starts on a controller and the unit tests pass. Nothing in it has been in the
air, and the release notes say so in their first line. The bench pass is the open work: the
whole Pre-Flight screen top to bottom, the locks refusing the keyboard, the channel list
against a live server (toggle one, watch a second client), the video-server switch with
passwords surviving it, and the in-flight channel dialog on a locked configuration.

What v1.2.0 brought: locks beside what they lock, quality-first video with two named server
slots and a pilot-selectable codec (H.264/H.265, wired through EncoderConfig and both
encode paths), server-held My Channels (`activebits` over the Marti API) reachable from
Pre-Flight AND from a touch-and-hold on the flight screen's TAK badge, and a Field Guide cut
with "Unknown marker" renamed to "Static marker".

v1.2.1 is documentation only: a second Field Guide cut, 3813 words to 3033, with no code
change and nothing different on the wire. **Do not chase the Autel guide's 2196 words** — it
documents eight controls fewer, including the warnings banner its own app has (conformance
A17), so its count is not this tree's target. The reasoning is in `FieldGuideActivity`'s
class doc with the measured figures.

The old local channel picker is REMOVED — its `<dest group>` made the server silently drop
markers, proved on the sibling 2026-08-15, which means **any fleet controller still on
v1.1.0 with a channel selected is losing every marker it sends.** `com.taklite` was
re-synced from the Autel tree at the same time (outbound CoT logging, `TakClient.checkError`,
`buildMarkerWithType`, `isLiveClient`). The `isLiveClient` team-dot rendering is NOT yet
consumed by this tree's map code.

The original Autel-parity pass is **flight-verified**: multiple sorties on 2026-08-12 confirmed the
warnings banner, contact retention (flat at 16 across a session), the operator marker, the
flight records, video on both CoT markers with a play control, AGL/DTED correction, the
Pre-Flight read-back and the fixed control response. `versionName` is `1.1.0`
(versionCode 5), released. The plan file is `~/.claude/plans/ok-i-have-my-atomic-castle.md`;
the Autel tree is the reference and lives at `../../Autel/AutelTAKPilot2/takpilot-autel_v1-2`.

Open items, in order of consequence:

1. **The Autel tree has the read-back placement bug.** `com.taklite` is shared by contract;
   the operator ports this separately. (The `__video` shape is no longer part of this item:
   the ConnectionEntry fix went to the Autel tree on 2026-08-12 and is flight-verified there.
   `CotParser.java` is now identical in both trees and `CotBuilder.java` differs only in the
   three airframe-identity constants. Keep it that way.)
2. **Yaw is measured, not wired.** The aircraft reports heading-turning smoothness 20/4/84
   across the three switch positions (logged each connect). Cine is identifiable as the
   smoothest value — wire Precision's yaw from the aircraft's own numbers, never from a
   guessed POSITION-to-name mapping.
3. **Control-response values are a starting point** — Normal 35 / Precision 15 felt right on
   the bench; nobody has tuned them in flight.
4. **Per-aircraft AR/FOV** for the planned Air 2S support.

Settled, and NOT open items:

- **The advertised video url carries credentials** (`user:pass@`). There is no other transport
  for them — the `ConnectionEntry` shape has no credential field, so a url without them does not
  authenticate and the feed does not play (operator, 2026-08-12). Do not raise it again and do
  not propose stripping them.

The printable Field Guide regenerates with `python3 tools/generate_field_guide_md.py` after
any FieldGuideActivity change. Output lands OUTSIDE the repo, beside the SDK folder.
