package be.digitalia.mediasession2mqtt.overlay

import android.graphics.Color
import org.json.JSONObject

enum class OverlayKind(val wireName: String) {
    TOAST("toast"),
    BANNER("banner"),
    POPUP("popup"),
    CAMERA("camera");

    companion object {
        fun fromWireName(name: String?): OverlayKind? = entries.firstOrNull { it.wireName == name }
    }
}

class OverlayButton(
    val id: String,
    val label: String,
    /** Whether pressing the button closes the overlay. */
    val dismiss: Boolean
)

/**
 * A notification to display over the running application.
 * [timeoutMillis] = 0 means the overlay stays until a button is pressed or Back is pressed.
 * [hasMedia] announces that image frames will follow for this [notificationId].
 */
class OverlayRequest(
    val notificationId: Int,
    val kind: OverlayKind,
    val primary: String,
    val secondary: String?,
    val icon: String?,
    val color: Int?,
    val timeoutMillis: Long,
    val buttons: List<OverlayButton>,
    val hasMedia: Boolean
)

class OverlayEvent(
    val notificationId: Int,
    /** "button" or "dismissed" */
    val type: String,
    val buttonId: String? = null,
    /** For "dismissed": "timeout", "back", "button", "replaced", "api" */
    val reason: String? = null
)

/**
 * Parse a "notify" message of the local API. Returns null when mandatory fields are missing or invalid.
 */
fun parseOverlayRequest(json: JSONObject): OverlayRequest? {
    val kind = OverlayKind.fromWireName(json.optString("kind")) ?: return null
    if (!json.has("notification_id")) return null
    val primary = json.optString("primary")
    if (primary.isEmpty()) return null
    val color = json.optString("color").takeIf { it.isNotEmpty() }?.let {
        try {
            Color.parseColor(it)
        } catch (_: IllegalArgumentException) {
            return null
        }
    }
    val buttons = ArrayList<OverlayButton>()
    json.optJSONArray("buttons")?.let { array ->
        for (i in 0 until minOf(array.length(), MAX_BUTTONS)) {
            val button = array.optJSONObject(i) ?: continue
            val label = button.optString("label")
            if (label.isEmpty()) continue
            buttons += OverlayButton(
                id = button.optString("id", i.toString()),
                label = label,
                dismiss = button.optBoolean("dismiss", true)
            )
        }
    }
    return OverlayRequest(
        notificationId = json.optInt("notification_id"),
        kind = kind,
        primary = primary,
        secondary = json.optString("secondary").takeIf { it.isNotEmpty() },
        icon = json.optString("icon").takeIf { it.isNotEmpty() },
        color = color,
        timeoutMillis = (json.optDouble("timeout", 0.0).coerceIn(0.0, MAX_TIMEOUT_SECONDS) * 1000).toLong(),
        buttons = buttons,
        hasMedia = json.optBoolean("has_media", false)
    )
}

private const val MAX_BUTTONS = 4
private const val MAX_TIMEOUT_SECONDS = 3600.0
