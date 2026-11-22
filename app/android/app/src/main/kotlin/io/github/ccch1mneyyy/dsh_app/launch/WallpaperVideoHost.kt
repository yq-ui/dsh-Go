package io.github.ccch1mneyyy.dsh_app.launch

import android.content.Context
import android.media.MediaPlayer
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
internal object WallpaperVideoHost {
    private var player: MediaPlayer? = null
    private var entry: TextureRegistry.SurfaceTextureEntry? = null
    private var surface: Surface? = null

    fun handle(
        context: Context,
        engine: FlutterEngine,
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        when (call.method) {
            "start" -> result.success(start(context, engine))
            "stop" -> {
                stop()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    /** Starts the loop and returns the texture's id, or null when the clip cannot be played. */
    private fun start(context: Context, engine: FlutterEngine): Long? {
        stop()
        // FlutterRenderer is the registry itself.
        val entry = engine.renderer.createSurfaceTexture()
        val surface = Surface(entry.surfaceTexture())
        val media = MediaPlayer()
        return try {
            context.resources.openRawResourceFd(R.raw.background).use { fd ->
                media.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            media.setSurface(surface)
            media.isLooping = true
            // Silent: a wallpaper that keeps its audio would play for hours, and would double up
            // with the opening animation's sound at start-up. 音效 belongs to the splash.
            media.setVolume(0f, 0f)
            media.setOnPreparedListener { it.start() }
            media.setOnErrorListener { _, _, _ -> true }
            media.prepareAsync()
            player = media
            this.entry = entry
            this.surface = surface
            entry.id()
        } catch (_: Exception) {
            media.release()
            surface.release()
            entry.release()
            null
        }
    }

    private fun stop() {
        player?.let { media ->
            runCatching {
                if (media.isPlaying) media.stop()
            }
            media.release()
        }
        player = null
        surface?.release()
        surface = null
        entry?.release()
        entry = null
    }
}
