package be.digitalia.mediasession2mqtt.localapi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.util.Log
import be.digitalia.mediasession2mqtt.mediasession.CurrentMediaControllerDetector
import be.digitalia.mediasession2mqtt.mediasession.MediaCommand
import be.digitalia.mediasession2mqtt.mediasession.execute
import be.digitalia.mediasession2mqtt.mediasession.metadataFlow
import be.digitalia.mediasession2mqtt.mediasession.parseMediaCommand
import be.digitalia.mediasession2mqtt.mediasession.playbackStateFlow
import be.digitalia.mediasession2mqtt.mqttmediaplayer.MQTTPlaybackState
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMQTTPlaybackStateOrNull
import be.digitalia.mediasession2mqtt.overlay.OverlayKind
import be.digitalia.mediasession2mqtt.overlay.OverlayManager
import be.digitalia.mediasession2mqtt.overlay.parseOverlayRequest
import be.digitalia.mediasession2mqtt.settings.LocalApiSettings
import be.digitalia.mediasession2mqtt.settings.SettingsProvider
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.java_websocket.WebSocket
import org.json.JSONObject

/**
 * Runs the local WebSocket API while it is enabled in the settings.
 * Independent from the MQTT connection.
 */
@Inject
class LocalApiService(
    private val context: Context,
    private val currentMediaControllerDetector: CurrentMediaControllerDetector,
    private val settingsProvider: SettingsProvider,
    private val overlayManager: OverlayManager
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val stateFlow: Flow<LocalApiState> =
        currentMediaControllerDetector.currentMediaController.flatMapLatest { mediaController ->
            if (mediaController == null) {
                flowOf(LocalApiState.NO_SESSION)
            } else {
                // Transient states are ignored (null), like for the MQTT topics
                val playbackStates = mediaController.playbackStateFlow
                    .mapNotNull { playbackState ->
                        val mqttState = if (playbackState == null) {
                            MQTTPlaybackState.Idle
                        } else {
                            playbackState.toMQTTPlaybackStateOrNull()
                        }
                        mqttState?.let { playbackState to it }
                    }
                combine(playbackStates, mediaController.metadataFlow) { (playbackState, mqttState), metadata ->
                    buildLocalApiState(
                        packageName = mediaController.packageName,
                        playbackState = playbackState,
                        mqttState = mqttState,
                        metadata = metadata,
                        elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                        nowEpochMillis = System.currentTimeMillis()
                    )
                }
            }
        }.distinctUntilChanged { old, new -> old.isEquivalentTo(new) }

    suspend fun run() {
        settingsProvider.localApiSettings.collectLatest { settings ->
            if (settings != null) {
                runServer(settings)
            }
        }
    }

    private suspend fun runServer(settings: LocalApiSettings) = coroutineScope {
        lateinit var server: LocalApiServer
        // WebSocket callbacks run on network threads, MediaController and windows must be used from the main thread
        server = LocalApiServer(settings.port, settings.token, object : LocalApiServer.Listener {
            override fun onCommand(client: WebSocket, request: LocalApiServer.CommandRequest) {
                launch(Dispatchers.Main.immediate) {
                    server.sendResult(client, request.id, handleCommand(request))
                }
            }

            override fun onNotify(client: WebSocket, id: Any?, message: JSONObject) {
                launch(Dispatchers.Main.immediate) {
                    server.sendResult(client, id, handleNotify(message))
                }
            }

            override fun onDismiss(client: WebSocket, id: Any?, kind: String?) {
                launch(Dispatchers.Main.immediate) {
                    val overlayKind = OverlayKind.fromWireName(kind)
                    if (kind != null && overlayKind == null) {
                        server.sendResult(client, id, "invalid_message")
                    } else {
                        if (overlayKind == null) overlayManager.dismissAll("api") else overlayManager.dismiss(overlayKind, "api")
                        server.sendResult(client, id, null)
                    }
                }
            }

            override fun onFrame(notificationId: Int, image: ByteArray) {
                launch(Dispatchers.Main.immediate) { overlayManager.updateFrame(notificationId, image) }
            }
        })
        overlayManager.eventListener = { event ->
            val json = JSONObject()
            json.put("event", event.type)
            json.put("notification_id", event.notificationId)
            event.buttonId?.let { json.put("button_id", it) }
            event.reason?.let { json.put("reason", it) }
            server.publishEvent(json)
        }
        server.start()
        val nsdRegistration = registerNetworkService(settings.port)
        try {
            stateFlow.collect { server.publishState(it) }
        } finally {
            overlayManager.eventListener = null
            overlayManager.dismissAll("api")
            withContext(NonCancellable + Dispatchers.IO) {
                nsdRegistration?.unregister()
                try {
                    server.stop(STOP_TIMEOUT_MILLIS)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Display a notification over the running application.
     * @return null on success, or an error code
     */
    private fun handleNotify(message: JSONObject): String? {
        if (!settingsProvider.isOverlayEnabled) {
            return "overlay_disabled"
        }
        val request = parseOverlayRequest(message) ?: return "invalid_message"
        return overlayManager.show(request)
    }

    /**
     * Apply the command to the current session.
     * @return null on success, or an error code
     */
    private fun handleCommand(request: LocalApiServer.CommandRequest): String? {
        val mediaController = currentMediaControllerDetector.currentMediaController.value ?: return "no_session"
        val command: MediaCommand = if (request.command == "seek") {
            val position = request.positionInMillis
            if (position == null || position < 0L) return "invalid_position"
            MediaCommand.SeekTo(position)
        } else {
            parseMediaCommand(request.command) ?: return "unknown_command"
        }
        if (!mediaController.playbackState.supports(command)) {
            return "unsupported"
        }
        return try {
            mediaController.execute(command)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The target application may have released its session in the meantime
            "failed"
        }
    }

    private class NsdRegistration(
        private val nsdManager: NsdManager,
        private val listener: NsdManager.RegistrationListener
    ) {
        fun unregister() {
            try {
                nsdManager.unregisterService(listener)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Announce the API on the local network (mDNS) to allow automatic discovery. Failures are not critical.
     */
    private fun registerNetworkService(port: Int): NsdRegistration? {
        return try {
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = NSD_SERVICE_NAME
                serviceType = NSD_SERVICE_TYPE
                setPort(port)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS registration failed: $errorCode")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            NsdRegistration(nsdManager, listener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS registration failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        private const val TAG = "LocalApiService"
        private const val STOP_TIMEOUT_MILLIS = 500
        private const val NSD_SERVICE_TYPE = "_src._tcp."
        private const val NSD_SERVICE_NAME = "SRC"
    }
}
