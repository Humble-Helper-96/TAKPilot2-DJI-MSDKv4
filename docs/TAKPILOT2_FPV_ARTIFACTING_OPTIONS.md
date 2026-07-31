# FPV Static-Scene Artifacting — Research Findings & Options

*Written 2026-07-30, after a web-research session prompted by "why do other Mini 2 apps
not have this problem?" This is a side-plan to try later, not active work. It builds on
(and partly revises) the artifacting conclusions in `TAKPILOT2_V4_PORT_PLAN.md`'s Archive —
notably, it reopens the Option-1/DJICodecManager question on new evidence, but ONLY for
non-Tensor hardware. Read the Archive's Option-1 dead-end entry alongside this.*

## The question

Our custom MediaCodec FPV pipeline accumulates static-scene artifacting after RF packet
loss (corruption sticks until motion, auto-resync, or the manual Video Re-Sync button).
Other apps flying the Mini 2 seem fine. Why — and what can we adopt?

## Findings (research 2026-07-30)

### 1. There is no cleaner video source — everyone gets our bytes

Every third-party MSDK v4 app (Litchi, RosettaDrone, DJI's own samples) consumes the same
`VideoFeeder` raw H.264 feed over the same lossy link, with the same Mini 2 downlink
behavior (no periodic IDR — we field-measured a 112 s keyframe gap). The difference is
not the source; it is **who decodes**.

### 2. The keyframe authority is the whole story

- Other apps hand the bytes to **`DJICodecManager`** (`sendDataToDecoder`). That makes
  DJI's decoder the *keyframe authority*: its internal decode-health tracking sees
  corruption as it happens and drives DJI's link-layer "send me an IDR" machinery.
  Corruption still appears at the moment of loss — but it self-heals in a second or two.
- **We decode ourselves**, so DJI's health tracker isn't decoding, stays "healthy," and
  never requests a keyframe. This matches our own field data exactly (Archive,
  2026-07-26): `resetDecoder()` no-ops while DJI thinks the stream is healthy, and works
  4/4 when DJI itself believes it's unsynced. The component allowed to ask the aircraft
  for an IDR has no idea anything is wrong.

### 3. Even DJI Fly has this problem

Community reports show lingering pixelation/stuck artifacts on DJI Fly's live feed with
clean recorded footage (the SD recording is a separate on-aircraft encode). The accepted
user workaround is "switch to the app's home page and back to camera view" — a manual
decoder resync, functionally identical to our Video Re-Sync button. So the gap between us
and DJI Fly is recovery *speed/automation*, not immunity.

### 4. Our DJICodecManager dead-end verdict may be device-specific

The Option-1 test (feeding DJICodecManager on the `option1-video` branch) failed on the
**Pixel 8 Pro — a Google Tensor device**. On Tensor, DJI's decoder was outright broken
for *everyone* — DJI Fly and Mimo included, total black screen on Pixel 6 — until DJI
patched the SDK in **4.16.1**. Litchi ships working Mini 2 live video on non-Tensor
phones using the same SDK decode path. Our current field device is the **RT3 — MediaTek**,
a decoder stack Option 1 was never tested against.

### 5. DJI's advanced sample exists and predates the Mini 2

`Android-VideoStreamDecodingSample` (and RosettaDrone's maintained fork) does FFmpeg
native frame parsing + **per-model canned I-frame injection from asset files** +
MediaCodec. There's no Mini 2 iframe asset (sample predates it), and RosettaDrone still
logged "streaming useless on Mini / Mini SE" issues — so this path is a parts-bin, not a
drop-in answer.

## Options, ranked

### Option A — Re-run DJICodecManager on the RT3  ← START HERE
Cheapest test, highest information. The `option1-video` branch still exists (worktree at
`SampleCode-option1-video/`). Build it, bench/fly it on the RT3 (MediaTek).
- If DJI's decoder behaves on MediaTek: field hardware gets DJI's self-healing recovery
  for free; keep the custom pipeline as the fallback for Tensor devices.
- If it fails the same way as on the Pixel 8 Pro: the dead-end verdict stands
  hardware-independently, and Option B is next.
- Note the DJI-key/applicationId constraint (Archive 2026-07-23): A/B testing is
  git-build-and-swap on the SAME applicationId, not side-by-side installs.
- Effort: an afternoon.

### Option B — Hybrid: wake the authority without using it for display
Run a `DJICodecManager` in YUV-callback mode (no surface) *alongside* our renderer,
purely so DJI's health tracking + IDR-request machinery are live; our pipeline keeps
drawing. Costs a second decode (CPU/thermal — recheck against the 90-min soak margins).
Known risk: the Mini-family YUV callback historically never fired
([Mobile-SDK-Android #592](https://github.com/dji-sdk/Mobile-SDK-Android/issues/592)) —
bench-test whether 4.18 fixed it before building anything on top.

### Option C — Sharpen our own loss-triggered resync (incremental, partly shipped)
Already in: `frame_num`-gap detection → auto-resync (`41758b0`), downlink-recovery resync
(`f71ae8d`). Residual artifacting is damage `frame_num` can't see (intra-frame slice
corruption). Deeper slice-level parsing is diminishing returns; tuning trigger
aggressiveness is free but each resync costs the brief flash.

### Option D — Software decode with error concealment (visual-quality mitigation)
Swap display decode to FFmpeg's software decoder, which conceals corrupted macroblocks
(interpolation from neighbors/previous frame) instead of leaving hard stale blocks like
MediaCodec. Doesn't recover lost data — changes the failure mode from "garbage that
sticks until resync" to "soft smear that fades." CPU fine at 720p. Independent of A/B —
could be combined with either. This is also the architecture family of DJI's own advanced
sample (minus the missing Mini 2 iframe asset).

### Option E — Status quo
Group A + auto-resync + manual Video Re-Sync; artifacting accepted. The outbound RTSP
stream is already structurally immune (screen-capture path). This is the fallback if A,
B, and D all disappoint.

## Recommendation

**A first** (afternoon, decisive either way) → **B** if MediaTek fails like Tensor →
**D** as an independent visual upgrade worth considering regardless of A/B outcome.

## Sources

- [Litchi what's-new — Pixel 6 live-feed note](https://flylitchi.com/whats-new)
- [XDA: Pixel 6 + DJI black screen, fixed in MSDK 4.16.1](https://www.xda-developers.com/dji-pixel-6-bugs/)
- [DJI forum: Pixel 6/6 Pro image transmission problems with GO 4](https://forum.dji.com/thread-274329-1-1.html)
- [Mobile-SDK-Android #592 — Mavic Mini YUV data not received](https://github.com/dji-sdk/Mobile-SDK-Android/issues/592)
- [RosettaDrone #122 — Mini-family streaming issues discussion](https://github.com/RosettaDrone/rosettadrone/issues/122)
- [RosettaDrone fork of the DJI decoding sample](https://github.com/RosettaDrone/DJI-Android-VideoStreamDecodingSample)
- [DJI Android-VideoStreamDecodingSample (FFmpeg parse + iframe injection)](https://github.com/DJI-Mobile-SDK-Tutorials/Android-VideoStreamDecodingSample)
- [MavicPilots: DJI Fly live-feed artifacts + switch-view-and-back workaround](https://mavicpilots.com/threads/pixelated-or-broken-appearance-on-screen.114735/)
- [DJICodecManager API docs](https://developer.dji.com/api-reference/android-api/Components/CodecManager/DJICodecManager.html)
