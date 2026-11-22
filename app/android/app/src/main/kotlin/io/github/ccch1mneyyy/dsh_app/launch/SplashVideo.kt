package io.github.ccch1mneyyy.dsh_app.launch

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.ccch1mneyyy.dsh_app.R

/**
 * Opening animation: the clip bundled as res/raw/splash.mp4 plays once, full screen and cropped to
 * cover it (a letterboxed 16:9 clip on a tall phone looks broken), with the clip's own audio, then
 * fades away to reveal Flutter. Cold starts only, and a fixed time limit guarantees the app can
 * never be held behind the video if playback stalls.
 */
internal object SplashVideo {
    /** The clip is 23.4s; the cap only guards against a decoder that never reports completion. */
    private const val LIMIT_MS = 30_000L

    fun playIfColdStart(activity: Activity, cold: Boolean) {
        if (!cold) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val frame = FrameLayout(activity)
        frame.setBackgroundColor(Color.BLACK)
        val view = CoverVideoView(activity)
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            frame,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        Overlay(activity, frame, view).start()
    }

    /**
     * One play-through: the clip, then a fade. [hideNow] runs at most once — from the end of the
     * clip, from a playback error, or from the time limit.
     */
    private class Overlay(
        private val activity: Activity,
        private val frame: FrameLayout,
        private val view: CoverVideoView,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var hidden = false
        private val hide = Runnable { hideNow() }

        fun start() {
            handler.postDelayed(hide, LIMIT_MS)
            view.play(R.raw.splash) { finished() }
        }

        private fun finished() {
            handler.removeCallbacks(hide)
            handler.postDelayed(hide, 120)
        }

        private fun hideNow() {
            if (hidden) return
            hidden = true
            handler.removeCallbacks(hide)
            view.release()
            // The clip is over: the screen may sleep again.
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            frame.animate().alpha(0f).setDuration(260).withEndAction {
                (frame.parent as? ViewGroup)?.removeView(frame)
            }.start()
        }
    }
}

/**
 * Plays one bundled clip, cropped to cover the whole view: scaled by the larger of the two ratios
 * and centred, so no part of the screen is left black whatever the clip's shape.
 */
internal class CoverVideoView(context: Context) :
    TextureView(context),
    TextureView.SurfaceTextureListener {

    private var player: MediaPlayer? = null
    private var pendingRes: Int? = null
    private var onDone: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
    }

    fun play(rawRes: Int, done: () -> Unit) {
        onDone = done
        pendingRes = rawRes
        surfaceTexture?.let { attach(it) }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        attach(texture)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        fill()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun release() {
        player?.runCatching {
            if (isPlaying) stop()
        }
        player?.release()
        player = null
    }

    private fun attach(texture: SurfaceTexture) {
        val res = pendingRes ?: return
        if (player != null) return
        pendingRes = null
        val media = MediaPlayer()
        try {
            context.resources.openRawResourceFd(res).use { fd ->
                media.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            media.setSurface(Surface(texture))
            media.isLooping = false
            // 音效: the clip's own track, copied from the export unchanged.
            media.setVolume(1f, 1f)
            media.setOnPreparedListener {
                fill()
                it.start()
            }
            media.setOnCompletionListener { onDone?.invoke() }
            media.setOnErrorListener { _, _, _ ->
                onDone?.invoke()
                true
            }
            media.prepareAsync()
            player = media
        } catch (_: Exception) {
            media.release()
            onDone?.invoke()
        }
    }

    /** Centre-crops the clip so it covers the view without distortion. */
    private fun fill() {
        val media = player ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val videoW = media.videoWidth.toFloat()
        val videoH = media.videoHeight.toFloat()
        if (w <= 0f || h <= 0f || videoW <= 0f || videoH <= 0f) return
        val scale = maxOf(w / videoW, h / videoH)
        val dx = (w - videoW * scale) / 2f
        val dy = (h - videoH * scale) / 2f
        setTransform(Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        })
    }
}
