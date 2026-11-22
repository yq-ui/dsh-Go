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
    private const val LIMIT_MS = 8000L

    fun playIfColdStart(activity: Activity, cold: Boolean) {
        if (!cold) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val frame = FrameLayout(activity)
        frame.setBackgroundColor(android.graphics.Color.BLACK)
        val video = VideoView(activity)
        video.setVideoURI(Uri.parse("android.resource://" + activity.packageName + "/" + R.raw.splash))
        video.setOnPreparedListener { player: MediaPlayer ->
            player.setVolume(0f, 0f)
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
