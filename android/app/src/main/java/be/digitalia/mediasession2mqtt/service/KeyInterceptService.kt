package be.digitalia.mediasession2mqtt.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import be.digitalia.mediasession2mqtt.inject.appGraph
import be.digitalia.mediasession2mqtt.overlay.OverlayManager
import dev.zacsweers.metro.Inject

/**
 * Receives the keys of the remote control before the application in the foreground, to let the user operate
 * the buttons of the notifications displayed over it. It does nothing, and consumes no key, while no
 * notification with buttons is displayed. It reads neither the screen content nor the accessibility events.
 */
class KeyInterceptService : AccessibilityService() {
    @Inject
    lateinit var overlayManager: OverlayManager

    override fun onCreate() {
        appGraph.inject(this)
        super.onCreate()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val consumed = overlayManager.handleKeyEvent(event)
        // Debug builds only: the logs of the release builds are removed
        Log.d(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} consumed=$consumed")
        return consumed
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "KeyInterceptService"

        @Volatile
        var isConnected = false
            private set
    }
}
