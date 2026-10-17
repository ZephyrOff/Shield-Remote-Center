"""Constants for SRC - Shield Remote Center."""

DOMAIN = "shield_remote_center"

CONF_TOKEN = "token"
DEFAULT_PORT = 8765
WS_PATH = "/ws"

# Event fired on the Home Assistant bus for button presses and dismissals
EVENT_NOTIFICATION = f"{DOMAIN}_event"

SERVICE_SEND_TOAST = "send_toast"
SERVICE_SEND_BANNER = "send_banner"
SERVICE_SEND_POPUP = "send_popup"
SERVICE_SEND_CAMERA = "send_camera"
SERVICE_DISMISS = "dismiss"

KIND_TOAST = "toast"
KIND_BANNER = "banner"
KIND_POPUP = "popup"
KIND_CAMERA = "camera"
KINDS = (KIND_TOAST, KIND_BANNER, KIND_POPUP, KIND_CAMERA)

# Seconds before the notification disappears when "timeout" is not given (0 = until dismissed)
DEFAULT_TIMEOUTS = {KIND_TOAST: 8, KIND_BANNER: 15, KIND_POPUP: 30, KIND_CAMERA: 30}

ATTR_PRIMARY = "primary"
ATTR_SECONDARY = "secondary"
ATTR_ICON = "icon"
ATTR_COLOR = "color"
ATTR_TIMEOUT = "timeout"
ATTR_BUTTONS = "buttons"
ATTR_CAMERA_ENTITY = "camera_entity"
ATTR_REFRESH_RATE = "refresh_rate"
ATTR_LIVE = "live"
ATTR_KIND = "kind"

DEFAULT_REFRESH_RATE = 1.0
# A live view without timeout is stopped after this duration
MAX_STREAM_SECONDS = 3600

# Android package name -> display name
APP_NAMES = {
    "com.google.android.youtube.tv": "YouTube",
    "com.netflix.ninja": "Netflix",
    "com.amazon.amazonvideo.livingroom": "Prime Video",
    "com.canal.android.canal": "Canal+",
    "com.disney.disneyplus": "Disney+",
    "com.apple.atve.android.appletv": "Apple TV",
    "org.videolan.vlc": "VLC",
}
