package io.github.ccch1mneyyy.dsh_app.launch

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.github.ccch1mneyyy.dsh_app.R

/**
 * The app's moving backdrop: the bundled clip plays on a loop behind the pages, muted, and is
 * cropped to fill the screen like a wallpaper. The file is shipped unchanged as
 * res/raw/background.mp4; nothing is downloaded and nothing outside the APK is read.
 */
class BackgroundVideoView(context: Context) :
    TextureView(context),
    PlatformView,
    TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null

    init {
        surfaceTextureListener = this
    }

    override fun getView(): View = this

    override fun dispose() {
        release()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        release()
        val media = MediaPlayer()
        try {
            context.resources.openRawResourceFd(R.raw.background).use { fd ->
                media.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            media.setSurface(Surface(texture))
            media.isLooping = true
            // Silent: a wallpaper loop that keeps its audio would play for hours and double up
            // with the opening animation's sound at start-up. 音效 belongs to the splash.
            media.setVolume(0f, 0f)
            media.setOnPreparedListener {
                it.start()
                fill(width, height)
            }
            media.prepareAsync()
            player = media
        } catch (_: Exception) {
            media.release()
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        fill(width, height)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    /** Centre-crops the clip so it covers the view without distortion. */
    private fun fill(width: Int, height: Int) {
        val media = player ?: return
        if (width <= 0 || height <= 0) return
        val videoW = media.videoWidth.takeIf { it > 0 } ?: return
        val videoH = media.videoHeight.takeIf { it > 0 } ?: return
        val scale = maxOf(width.toFloat() / videoW, height.toFloat() / videoH)
        val dx = (width - videoW * scale) / 2f
        val dy = (height - videoH * scale) / 2f
        setTransform(Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        })
    }

    private fun release() {
        player?.runCatching {
            if (isPlaying) stop()
        }
        player?.release()
        player = null
    }

    class Factory : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
        override fun create(context: Context, id: Int, args: Any?): PlatformView = BackgroundVideoView(context)
    }
}
