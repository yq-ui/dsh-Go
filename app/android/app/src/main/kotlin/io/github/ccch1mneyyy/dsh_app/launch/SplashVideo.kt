package io.github.ccch1mneyyy.dsh_app.launch

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
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

    fun playIfColdStart(activity: Activity, cold: Boolean) {
        if (!cold) return
        // The engine boots while this plays (LaunchStart runs first), so the animation covers the
        // start-up instead of delaying it; keep the screen on so it is not dimmed mid-clip.
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val frame = FrameLayout(activity)
        frame.setBackgroundColor(android.graphics.Color.BLACK)
        val video = VideoView(activity)
        video.setVideoURI(Uri.parse("android.resource://" + activity.packageName + "/" + R.raw.splash))
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
        Overlay(activity, frame, video).start()
    }

    /**
     * One play-through: the clip, then a fade. [hideNow] runs at most once — from the end of the
     * clip, from a playback error, or from the time limit.
     */
    private class Overlay(
        private val activity: Activity,
        private val frame: FrameLayout,
        private val video: VideoView,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var hidden = false
        private val hide = Runnable { hideNow() }

        fun start() {
            handler.postDelayed(hide, LIMIT_MS)
            video.setOnCompletionListener { finished() }
            video.setOnErrorListener { _, _, _ ->
                finished()
                true
            }
        }

        private fun finished() {
            handler.removeCallbacks(hide)
            handler.postDelayed(hide, 120)
        }

        private fun hideNow() {
            if (hidden) return
            hidden = true
            handler.removeCallbacks(hide)
            // The clip is over: the screen may sleep again.
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            frame.animate().alpha(0f).setDuration(260).withEndAction {
                (frame.parent as? ViewGroup)?.removeView(frame)
            }.start()
        }
    }
}
