package io.github.ccch1mneyyy.dsh_app.launch

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import android.widget.VideoView
import io.github.ccch1mneyyy.dsh_app.R

/**
 * Opening animation: the clip bundled as res/raw/splash.mp4 plays once, full width, with its own
 * audio, then fades away to reveal Flutter. Cold starts only, and a fixed time limit guarantees the
 * app can never be held behind the video if playback stalls.
 *
 * The clip keeps its own proportions — it is 16:9, so a tall screen shows it with bars top and
 * bottom. Cropping it to cover was tried and looked worse; the bars stay.
 */
internal object SplashVideo {
    /** The clip is 23.4s; the cap only guards against a decoder that never reports completion. */
    private const val LIMIT_MS = 30_000L

    fun playIfColdStart(activity: Activity, cold: Boolean, onRemoved: () -> Unit): Boolean {
        if (!cold) return false
        // The engine boots while this plays (LaunchStart runs first), so the animation covers the
        // start-up instead of delaying it; keep the screen on so it is not dimmed mid-clip.
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val frame = FrameLayout(activity).apply { keepScreenOn = true }
        frame.setBackgroundColor(android.graphics.Color.BLACK)
        val video = VideoView(activity)
        video.setOnPreparedListener { player ->
            // 音效: the clip's own track, copied from the export unchanged.
            player.setVolume(1f, 1f)
            player.isLooping = false
            video.start()
        }
        frame.addView(
            video,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        root.addView(
            frame,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        Overlay(frame, video, onRemoved).start()
        video.setVideoURI(Uri.parse("android.resource://" + activity.packageName + "/" + R.raw.splash))
        return true
    }

    /**
     * One play-through: the clip, then a fade. [hideNow] runs at most once — from the end of the
     * clip, from a playback error, or from the time limit.
     */
    private class Overlay(
        private val frame: FrameLayout,
        private val video: VideoView,
        private val onRemoved: () -> Unit,
    ) : View.OnAttachStateChangeListener {
        private val handler = Handler(Looper.getMainLooper())
        private var hidden = false
        private val hide = Runnable { hideNow() }

        override fun onViewAttachedToWindow(v: View) {
            // A detached window can be reused. Never reattach an inert black overlay.
            if (hidden) frame.post { removeOverlay() }
        }

        override fun onViewDetachedFromWindow(v: View) {
            hidden = true
            handler.removeCallbacks(hide)
            frame.animate().cancel()
            frame.keepScreenOn = false
            video.setOnPreparedListener(null)
            video.setOnCompletionListener(null)
            video.setOnErrorListener(null)
        }

        fun start() {
            frame.addOnAttachStateChangeListener(this)
            handler.postDelayed(hide, LIMIT_MS)
            video.setOnCompletionListener { finished() }
            video.setOnErrorListener { _, _, _ ->
                finished()
                true
            }
        }

        private fun finished() {
            if (hidden) return
            handler.removeCallbacks(hide)
            handler.postDelayed(hide, 120)
        }

        private fun hideNow() {
            if (hidden) return
            hidden = true
            handler.removeCallbacks(hide)
            // This view owns its wake request; other features retain theirs.
            frame.keepScreenOn = false
            frame.animate().alpha(0f).setDuration(260).withEndAction { removeOverlay() }.start()
        }

        private fun removeOverlay() {
            val parent = frame.parent as? ViewGroup ?: return
            parent.removeView(frame)
            onRemoved()
        }
    }
}
