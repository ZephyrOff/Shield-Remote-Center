package be.digitalia.mediasession2mqtt.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import be.digitalia.mediasession2mqtt.service.KeyInterceptService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Shows toasts, banners, popups and camera views over the running application, using the
 * "display over other apps" permission. At most one overlay per [OverlayKind] is shown at a time.
 *
 * All the methods must be called from the main thread.
 *
 * Overlays with buttons take the focus so that they can be operated with the remote control: they are
 * dismissed with the Back key. Overlays without buttons never take the focus nor the touches.
 */
@Inject
@SingleIn(AppScope::class)
class OverlayManager(private val context: Context) {
    private class ActiveOverlay(
        val request: OverlayRequest,
        val root: View,
        val image: ImageView?,
        val timeoutRunnable: Runnable?,
        val buttons: List<View>,
        /** Increasing: the overlay shown last receives the keys. */
        val order: Long,
        val video: TextureView?
    ) {
        var selected = 0
        var player: ExoPlayer? = null
        var videoSize: VideoSize? = null
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val active = EnumMap<OverlayKind, ActiveOverlay>(OverlayKind::class.java)

    private var shownCount = 0L

    private val decoder = Executors.newSingleThreadExecutor()
    private val isDecoding = AtomicBoolean(false)

    /** Receives button presses and dismissals. Called on the main thread. */
    var eventListener: ((OverlayEvent) -> Unit)? = null

    fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    /**
     * @return null on success, or an error code
     */
    fun show(request: OverlayRequest): String? {
        if (!canDrawOverlays()) {
            return "overlay_permission"
        }
        dismiss(request.kind, "replaced")

        val built = try {
            OverlayViewFactory(context).build(
                request,
                onButton = { button -> onButtonPressed(request, button) },
                onBack = { dismiss(request.kind, "back", request.notificationId) }
            )
        } catch (_: Exception) {
            return "overlay_failed"
        }
        try {
            windowManager.addView(built.root, createLayoutParams(request))
        } catch (_: Exception) {
            return "overlay_failed"
        }
        val timeoutRunnable = if (request.timeoutMillis > 0L) {
            Runnable { dismiss(request.kind, "timeout", request.notificationId) }.also {
                handler.postDelayed(it, request.timeoutMillis)
            }
        } else {
            null
        }
        val overlay = ActiveOverlay(request, built.root, built.image, timeoutRunnable, built.buttons, ++shownCount, built.video)
        active[request.kind] = overlay
        updateSelection(overlay)
        // When the window has the focus, the selection follows it
        overlay.buttons.forEachIndexed { index, view ->
            view.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    overlay.selected = index
                    updateSelection(overlay)
                }
            }
        }
        // With the key interception service, the selection is drawn by the manager and the window has no focus
        if (!KeyInterceptService.isConnected) {
            built.firstFocus?.post { built.firstFocus.requestFocus() }
        }
        startStream(overlay)
        return null
    }

    /**
     * Play the live stream of the notification in its video surface, muted, with a short buffer.
     * The pictures stay displayed until the first video frame, and again if the stream fails.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun startStream(overlay: ActiveOverlay) {
        val url = overlay.request.streamUrl ?: return
        val video = overlay.video ?: return
        try {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(1_000, 4_000, 500, 1_000)
                .build()
            val player = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
            overlay.player = player
            player.volume = 0f
            player.setVideoTextureView(video)
            video.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> overlay.videoSize?.let { applyVideoTransform(video, it) } }
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    overlay.image?.visibility = View.GONE
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    overlay.videoSize = videoSize
                    applyVideoTransform(video, videoSize)
                }

                override fun onPlayerError(error: PlaybackException) {
                    streamFailed(overlay)
                }
            })
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(url)
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(2_000)
                            .setMaxPlaybackSpeed(1.08f)
                            .build()
                    )
                    .build()
            )
            player.playWhenReady = true
            player.prepare()
        } catch (_: Exception) {
            streamFailed(overlay)
        }
    }

    /** Fill the surface with the video, cropping it like the pictures. */
    private fun applyVideoTransform(video: TextureView, size: VideoSize) {
        val viewWidth = video.width.toFloat()
        val viewHeight = video.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f || size.width == 0 || size.height == 0) {
            return
        }
        val videoAspect = size.width * size.pixelWidthHeightRatio / size.height
        val viewAspect = viewWidth / viewHeight
        // The texture is stretched to the view: restore the aspect ratio of the video, then fill the view
        val matrix = Matrix()
        if (videoAspect > viewAspect) {
            matrix.setScale(videoAspect / viewAspect, 1f, viewWidth / 2, viewHeight / 2)
        } else {
            matrix.setScale(1f, viewAspect / videoAspect, viewWidth / 2, viewHeight / 2)
        }
        video.setTransform(matrix)
    }

    /** The stream can not be played: show the pictures again and tell the client, which sends them. */
    private fun streamFailed(overlay: ActiveOverlay) {
        releasePlayer(overlay)
        overlay.image?.visibility = View.VISIBLE
        if (active[overlay.request.kind] === overlay) {
            eventListener?.invoke(OverlayEvent(overlay.request.notificationId, "stream_failed"))
        }
    }

    private fun releasePlayer(overlay: ActiveOverlay) {
        overlay.player?.let {
            overlay.player = null
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Handle a key of the remote control while an overlay with buttons is displayed: the arrows select a
     * button, OK presses it and Back closes the overlay. Called by the key interception service, which
     * receives the keys before the application in the foreground.
     *
     * @return true if the key was consumed
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val overlay = active.values.filter { it.buttons.isNotEmpty() }.maxByOrNull { it.order } ?: return false
        val isDown = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> if (isDown) moveSelection(overlay, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> if (isDown) moveSelection(overlay, 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (event.action == KeyEvent.ACTION_UP) {
                    overlay.request.buttons.getOrNull(overlay.selected)?.let { onButtonPressed(overlay.request, it) }
                }

            KeyEvent.KEYCODE_BACK ->
                if (event.action == KeyEvent.ACTION_UP) {
                    dismiss(overlay.request.kind, "back", overlay.request.notificationId)
                }

            else -> return false
        }
        return true
    }

    private fun moveSelection(overlay: ActiveOverlay, step: Int) {
        val count = overlay.buttons.size
        overlay.selected = (overlay.selected + step + count) % count
        updateSelection(overlay)
    }

    private fun updateSelection(overlay: ActiveOverlay) {
        overlay.buttons.forEachIndexed { index, view -> view.isSelected = index == overlay.selected }
    }

    /**
     * Dismiss the overlay of this kind, if any. When [notificationId] is given, only if it still shows it.
     */
    fun dismiss(kind: OverlayKind, reason: String, notificationId: Int? = null) {
        val overlay = active[kind] ?: return
        if (notificationId != null && overlay.request.notificationId != notificationId) {
            return
        }
        active.remove(kind)
        overlay.timeoutRunnable?.let { handler.removeCallbacks(it) }
        releasePlayer(overlay)
        try {
            windowManager.removeView(overlay.root)
        } catch (_: Exception) {
        }
        eventListener?.invoke(OverlayEvent(overlay.request.notificationId, "dismissed", reason = reason))
    }

    fun dismissAll(reason: String) {
        for (kind in OverlayKind.entries) {
            dismiss(kind, reason)
        }
    }

    /**
     * Display a new JPEG or PNG image on the overlay showing media for this notification.
     * Frames are dropped while the previous one is still being decoded.
     */
    fun updateFrame(notificationId: Int, data: ByteArray) {
        val overlay = active.values.firstOrNull { it.request.notificationId == notificationId && it.image != null }
            ?: return
        if (!isDecoding.compareAndSet(false, true)) {
            return
        }
        decoder.execute {
            try {
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options)
                if (bitmap != null) {
                    handler.post {
                        // The overlay may have been replaced in the meantime
                        if (active[overlay.request.kind] === overlay) {
                            overlay.image?.setImageBitmap(bitmap)
                        }
                    }
                }
            } catch (_: Exception) {
                // Corrupted frame: wait for the next one
            } finally {
                isDecoding.set(false)
            }
        }
    }

    private fun onButtonPressed(request: OverlayRequest, button: OverlayButton) {
        if (active[request.kind]?.request !== request) {
            return
        }
        eventListener?.invoke(OverlayEvent(request.notificationId, "button", buttonId = button.id))
        if (button.dismiss) {
            dismiss(request.kind, "button", request.notificationId)
        }
    }

    private fun createLayoutParams(request: OverlayRequest): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        flags = flags or when {
            request.buttons.isEmpty() ->
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // The keys are received through the key interception service: don't take the focus
            // away from the application which is playing
            KeyInterceptService.isConnected -> WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            // Otherwise rely on the focus of the window
            else -> WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        }
        val scale = context.resources.displayMetrics.widthPixels / REFERENCE_WIDTH
        val params = WindowManager.LayoutParams(
            if (request.kind == OverlayKind.POPUP) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            if (request.kind == OverlayKind.POPUP) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )
        when (request.kind) {
            OverlayKind.TOAST, OverlayKind.BANNER -> {
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = (40 * scale).roundToInt()
            }

            OverlayKind.CAMERA -> {
                params.gravity = Gravity.TOP or Gravity.END
                params.x = (48 * scale).roundToInt()
                params.y = (40 * scale).roundToInt()
            }

            OverlayKind.POPUP -> params.gravity = Gravity.BOTTOM
        }
        return params
    }

    private companion object {
        private const val REFERENCE_WIDTH = 1280f
    }
}
