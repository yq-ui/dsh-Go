package io.github.ccch1mneyyy.dsh_app.launch

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import io.github.ccch1mneyyy.dsh_app.R

/**
 * The moving backdrop, played into a Flutter texture.
 *
 * A platform view would sit in the activity's view hierarchy, above the app's own UI on this
 * device — the clip then covered every page instead of staying behind it. A texture is part of
 * Flutter's own scene, so the pages are always drawn over it.
 *
 * The clip is the bundled res/raw/background.mp4, muted and looped; nothing is downloaded and
 * nothing outside the APK is read.
 */
internal class WallpaperVideoHost {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stop() }
    private var player: MediaPlayer? = null
    private var entry: TextureRegistry.SurfaceTextureEntry? = null
    private var surface: Surface? = null
    private var coveredBySplash = false
    private var deferredStart: (() -> Unit)? = null
    private var pending: MethodChannel.Result? = null

    fun setSplashCovered(covered: Boolean) {
        coveredBySplash = covered
        if (!covered) {
            val start = deferredStart
            deferredStart = null
            start?.invoke()
        }
    }
    private var onEnded: (() -> Unit)? = null

    fun handle(context: Context, engine: FlutterEngine, channel: MethodChannel, call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> {
                val request = call.arguments as? Number
                stop()
                if (request == null) {
                    result.success(null)
                    return
                }
                pending = result
                val begin = { start(context, engine) { channel.invokeMethod("ended", request) } }
                if (coveredBySplash) deferredStart = begin else begin()
            }
            "stop" -> {
                stop()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun start(context: Context, engine: FlutterEngine, ended: () -> Unit) {
        onEnded = ended
        try {
            val texture = engine.renderer.createSurfaceTexture().also { entry = it }
            val output = Surface(texture.surfaceTexture()).also { surface = it }
            val media = MediaPlayer().also { player = it }
            context.resources.openRawResourceFd(R.raw.background).use { fd ->
                media.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            media.setSurface(output)
            media.isLooping = true
            media.setVolume(0f, 0f)
            media.setOnPreparedListener {
                if (it === player) try { it.start() } catch (_: IllegalStateException) { stop() }
            }
            media.setOnInfoListener { source, what, _ ->
                if (source === player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    handler.removeCallbacks(timeout)
                    pending?.success(texture.id())
                    pending = null
                }
                false
            }
            media.setOnErrorListener { source, _, _ ->
                if (source === player) stop()
                true
            }
            handler.postDelayed(timeout, 5000L)
            media.prepareAsync()
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        deferredStart = null
        handler.removeCallbacks(timeout)
        val media = player
        player = null
        media?.release()
        surface?.release()
        surface = null
        entry?.release()
        entry = null
        val waiting = pending
        pending = null
        if (waiting != null) waiting.success(null) else onEnded?.invoke()
        onEnded = null
    }
}
