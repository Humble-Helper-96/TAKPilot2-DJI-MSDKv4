package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import com.taklite.util.AppLog
import android.view.TextureView
import dji.midware.usb.P3.UsbAccessoryService
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager

/**
 * Live FPV view — OPTION 1 pipeline (option1-video fork).
 *
 * Unlike the main-branch custom-MediaCodec pipeline, this hands the whole decode+render job to
 * DJI's own [DJICodecManager], rendering directly into this TextureView's SurfaceTexture and
 * feeding it the raw H.264 bytes from VideoFeeder — exactly the path the stock DJI sample's
 * production FPV widgets (BaseFpvView / VideoFeedView) and DJI Fly itself use.
 *
 * Why: the custom pipeline split decoding (our MediaCodec) from keyframe-request authority
 * (a dormant, never-fed DJICodecManager). DJI's decoder auto-requests a recovery IDR the
 * instant IT hits a decode error — but only for the stream IT is decoding. With decode split
 * off to our MediaCodec, that feedback loop was severed: our decoder accumulated macroblock
 * corruption over a static scene and had no working way to command a fresh IDR (the dormant
 * lever, decoding nothing, ignored the requests). Putting decode back where the keyframe
 * authority lives restores DJI Fly's self-healing behavior — no periodic resync/stutter.
 *
 * The Mini 2 emits no IDR in steady state (only on request), so we prime one [resetKeyFrame]
 * as soon as bytes start flowing; after that DJICodecManager handles error-triggered recovery
 * itself. The aspect-ratio pillarbox transform + sibling CrosshairView plumbing are unchanged
 * from the main branch — DJICodecManager renders into the same SurfaceTexture our MediaCodec
 * used, so setTransform/onVideoRectChanged still apply identically.
 */
class FpvTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var videoListener: VideoFeeder.VideoDataListener? = null
    private var codecManager: DJICodecManager? = null
    @Volatile private var sawFirstFrame = false

    /** Invoked (on the DJI callback thread) the first time raw video bytes arrive. */
    var onFirstFrame: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // DJICodecManager decodes AND renders into this TextureView's SurfaceTexture (same as
        // the stock BaseFpvView). Created per-surface — the DJI-sanctioned lifecycle; unlike
        // the dormant keyframe-lever churn that wedged the native engine on the main branch,
        // a fed/active manager recreated per surface is exactly what DJI Fly does.
        if (codecManager == null) {
            codecManager = DJICodecManager(
                context.applicationContext, surface, width, height,
                UsbAccessoryService.VideoStreamSource.Camera,
            ).also { cm ->
                cm.setOnVideoSizeChangedListener { w, h -> post { applyAspect(w, h) } }
            }
        }

        val feed = VideoFeeder.getInstance()?.primaryVideoFeed
        if (feed != null && videoListener == null) {
            val l = VideoFeeder.VideoDataListener { data, size ->
                if (!sawFirstFrame) {
                    sawFirstFrame = true
                    onFirstFrame?.invoke()
                    // Prime the initial IDR: the Mini 2 sends none until asked, so a fresh
                    // decode session would otherwise sit blank/sparse until a chance keyframe.
                    // After this, DJICodecManager requests recovery keyframes on decode error
                    // on its own.
                    runCatching { codecManager?.resetKeyFrame() }
                }
                runCatching {
                    codecManager?.sendDataToDecoder(
                        data, size, UsbAccessoryService.VideoStreamSource.Camera.getIndex(),
                    )
                }
            }
            feed.addVideoDataListener(l)
            videoListener = l
            AppLog.i(TAG, "FPV(opt1): DJICodecManager started (${width}x$height), listener registered")
        } else if (feed == null) {
            AppLog.w(TAG, "FPV(opt1): no primary video feed available yet")
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        val (w, h) = videoSize
        if (w > 0 && h > 0) applyAspect(w, h)
    }

    // ---- Aspect-ratio handling ----
    @Volatile private var videoSize = 0 to 0

    /**
     * Letterbox/pillarbox the decoded video (fit-left). A TextureView stretches its content
     * to the view bounds by default, which distorted the 16:9 feed on the wider flight-screen
     * view; setTransform corrects it using the real video size reported by the decoder.
     * Pillarbox space is pinned to the right edge (pivot x=0) rather than split evenly on both
     * sides, so the video hugs the left edge and leaves one contiguous blank strip on the right
     * for HUD content — vertical letterboxing (rare on this landscape screen) still centers.
     */
    private fun applyAspect(videoW: Int, videoH: Int) {
        videoSize = videoW to videoH
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || videoW <= 0 || videoH <= 0) return
        val viewAspect = vw / vh
        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val m = Matrix()
        if (videoAspect > viewAspect) {
            // Video wider than view: full width, shrink height (letterbox top/bottom).
            m.setScale(1f, viewAspect / videoAspect, 0f, vh / 2f)
            val h = vh * (viewAspect / videoAspect)
            videoRect.set(0f, vh / 2f - h / 2f, vw, vh / 2f + h / 2f)
        } else {
            // Video taller than view: full height, shrink width — pivot at the left edge so
            // the blank strip lands entirely on the right instead of split both sides.
            m.setScale(videoAspect / viewAspect, 1f, 0f, vh / 2f)
            videoRect.set(0f, 0f, vw * (videoAspect / viewAspect), vh)
        }
        setTransform(m)
        onVideoRectChanged?.invoke(videoRect)
    }

    // ---- Video content rect, for the sibling CrosshairView ----
    // TextureView.onDraw()/draw() are both final in this SDK (it owns SurfaceTexture
    // rendering), so the crosshair can't be drawn inside this view — instead we just track and
    // publish the actual video content bounds (not the screen's — the video is left-
    // pillarboxed, so its center isn't the view's center) for a sibling overlay view to use.
    private val videoRect = RectF()

    /** Invoked whenever the video content's on-screen bounds change (aspect/size updates). */
    var onVideoRectChanged: ((RectF) -> Unit)? = null

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        videoListener?.let { VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(it) }
        videoListener = null
        try { codecManager?.cleanSurface(); codecManager?.destroyCodec() } catch (_: Throwable) {}
        codecManager = null
        sawFirstFrame = false
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    companion object {
        private const val TAG = "FpvTextureView"
    }
}
