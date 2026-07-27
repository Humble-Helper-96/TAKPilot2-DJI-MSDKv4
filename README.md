# TAKPilot2 Go — DJI Mobile SDK V4 port

TAKPilot2 for the **DJI Mini 2 / RC-N1**, built on **DJI Mobile SDK 4.18**. The app flies the
aircraft, streams live position/attitude/battery to a TAK server as CoT, pushes the FPV video to
a media server as RTSP, drops and manages TAK markers, and projects markers onto the live video
as an AR overlay.

This exists because TAKPilot2 proper is built on **MSDK V5, which does not support the Mini 2**.
Rather than a literal port, the SDK-agnostic core (TAK client, CoT, enrollment, channel scoping)
was reused unchanged and every DJI-touching piece was rewritten against V4's callback APIs. A
large amount of the UI is net-new, driven by field testing.

## Where the code is

This tree is DJI's stock `Sample Code` app with the TAKPilot2 work layered on top — about
**98 files changed against the stock sample, 67 of them new.** Everything else is DJI's and
untouched. To see only the port:

```bash
git diff --stat baseline-current HEAD
```

| Package | What |
|---|---|
| `com/dji/sdk/sample/takpilot2/` | Flight screen, home screen, custom views (FPV decoder, crosshair, AR overlay, HUD widgets), field guide |
| `com/dji/sdk/sample/tak/` | DJI↔TAK bridge, CoT push, DTED terrain, FAA UASFM, markers, video streaming, exposure/limits controllers |
| `com/taklite/` | SDK-agnostic TAK core — reused from TAKPilot2 essentially unchanged |
| `com/pedro/rtsp/` | **Vendored** RootEncoder RTSP client (Apache-2.0, see its `NOTICE.txt`) |
| `docs/` | Design docs — read these first, see below |

`com.pedro.rtsp` is vendored as source rather than pulled as a Gradle dependency deliberately:
the published artifacts target JDK-17 bytecode, which this tree's AGP 4.2.2 D8/R8 cannot dex.
Compiling the source against our own Java 8 target sidesteps that permanently.

## Read the docs first

`docs/` carries the full design record — not just what was built but why, including the dead
ends, the field-measured findings, and the things that look like bugs but aren't.

| Doc | Read when |
|---|---|
| `TAKPILOT2_V4_PORT_PLAN.md` | **Start here.** Current status, architecture reference, environment, open items |
| `TAKPILOT2_V4_PORT_SUMMARY.md` | Shorter narrative overview of the whole port |
| `TAKPILOT2_PHASE6_PLAN.md` | Markers / dropped pins |
| `TAKPILOT2_PHASE6D_PLAN.md` | AR overlay — read before touching `ArOverlayView` |

They are snapshots, not a live view. `git log --oneline` is the reliable changelog; re-verify
any claim against the source before writing code against it.

## Building

Pinned toolchain — **these versions matter**, the tree does not build on newer ones without
work (see the vendoring note above):

- Gradle **6.7.1**, AGP **4.2.2**
- JDK **11**
- Kotlin **1.5.10**

```bash
ANDROID_SDK_ROOT=<your-sdk> JAVA_HOME=<your-jdk-11> ./gradlew :app:assembleDebug
```

### You need your own DJI API key

`app/src/main/AndroidManifest.xml` carries a `com.dji.sdk.API_KEY` value. **It will not work for
you.** DJI validates the key against the `applicationId`, not the manifest package, so the
committed key is bound to `com.anchortak.takpilot2gov4` and registers only for that ID.

Register your own app at the DJI developer portal and replace the key. If you also change the
`applicationId`, the key must be registered against the new one — this is also why side-by-side
installs and build flavors don't work here, and why A/B testing is build-and-swap from git
branches rather than parallel installs.

## Runtime configuration

No server details, certificates or credentials are in this repo. TAK enrollment, server host,
channels, video destination and DTED tiles are all configured in-app under **Pre-Flight Setup**
and stored on the device. A fresh install starts empty.

## Hardware notes

Developed and field-tested against a **Mini 2 with RC-N1** on a Pixel 8 Pro. The video pipeline
in particular is tuned to Mini 2 behaviour — notably that it emits no periodic SPS/PPS/IDR, only
on request, which drove the custom MediaCodec decoder in `FpvTextureView.kt`. That file is the
most fragile piece in the app; re-test on real hardware after any change near it. The port plan's
Archive section explains what was already tried there and ruled out.

## Status

Phases 1–6 complete and field-confirmed, including the AR overlay. Known open items — FOV
calibration, a residual SPoI bearing error, ADS-B latency, and the contact-altitude question —
are listed with context at the top of `docs/TAKPILOT2_V4_PORT_PLAN.md`.
