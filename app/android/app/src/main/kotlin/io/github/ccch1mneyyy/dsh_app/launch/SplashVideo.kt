package io.github.ccch1mneyyy.dsh_app.launch

import android.app.Activity
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import io.github.ccch1mneyyy.dsh_app.R

/**
 * Opening animation: the clip bundled as res/raw/splash.mp4 plays once, full screen, then fades
 * away to reveal Flutter. Cold starts only, and a fixed time limit guarantees the app can never be
 * held behind the video if playback stalls.
 */
internal object SplashVideo {
    /** The clip is 23.4s; the cap only guards against a decoder that never reports completion. */
    private const val LIMIT_MS = 30_000L

    fun playIfColdStart(activity: Activity, cold: Boolean) {
        if (!cold) return
        // The engine boots while this plays (LaunchStart runs first), so the animation covers the
        // start-up instead of delaying it; keep the screen on so it is not dimmed mid-clip.
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val frame = FrameLayout(activity)
        frame.setBackgroundColor(android.graphics.Color.BLACK)
        val video = VideoView(activity)
        video.setVideoURI(Uri.parse("android.resource://" + activity.packageName + "/" + R.raw.splash))
        video.setOnPreparedListener { player: MediaPlayer ->
            // 音效: the clip's own track, unchanged from the export.
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
        val hide = Runnable { hide(frame) }
        video.setOnCompletionListener { frame.postDelayed(hide, 120) }
        video.setOnErrorListener { _, _, _ -> frame.postDelayed(hide, 0); true }
        Handler(Looper.getMainLooper()).postDelayed(hide, LIMIT_MS)
    }

    private fun hide(frame: View) {
        if (frame.parent == null) return
        frame.animate().alpha(0f).setDuration(260).withEndAction {
            (frame.parent as? ViewGroup)?.removeView(frame)
        }.start()
    }
}
