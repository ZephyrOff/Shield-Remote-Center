# SRC - Shield Remote Center

Home Assistant integration to control an Android TV device (target: NVIDIA Shield) and display things on its screen, with **local push** and no Home Assistant token on the TV:

- **Media control**: a real `media_player` entity (state, title, position, play / pause / stop / next / previous / seek).
- **Notifications on screen**: toasts, banners, popups and live camera views, with buttons that can run scripts or any action.

It talks to the companion Android app in [`android/`](android) (a fork of [MediaSession2MQTT](https://github.com/cbeyls/MediaSession2MQTT)), which serves a local WebSocket API protected by a pairing token.

## Repository layout

- `custom_components/shield_remote_center`: the Home Assistant integration (what HACS installs).
- `android/`: the companion Android app. Build with `./gradlew assembleDebug` using JDK 21+.

## Installation

### HACS (custom repository)

1. HACS → ⋮ → *Custom repositories* → add this repository, category *Integration*.
2. Install **SRC - Shield Remote Center** and restart Home Assistant.

### Manual

Copy `custom_components/shield_remote_center` to your `config/custom_components/` and restart Home Assistant.

> The integration was previously called *Shield Media* (domain `shield_media`). Remove the old integration and add this one again.

## Setup

1. On the Shield, install the app, then grant notification access (needed for the media control):
   ```
   adb shell cmd notification allow_listener be.digitalia.mediasession2mqtt/be.digitalia.mediasession2mqtt.service.MediaSessionListenerService
   ```
2. In the app settings, enable **Enable local API** and note the **pairing token**.
3. To display notifications, also enable **Allow notifications over other apps** and grant the "display over other apps" permission (the settings entry opens the system screen when it exists; otherwise use ADB):
   ```
   adb shell appops set be.digitalia.mediasession2mqtt SYSTEM_ALERT_WINDOW allow
   ```
4. In Home Assistant: *Settings → Devices & services → Add integration → SRC*, then enter the Shield IP address, the port (default `8765`) and the token.

The traffic is an unencrypted WebSocket on the local network: do not expose the port outside a trusted network.

## Actions

The actions are available on the `media_player` entity of the Shield (choose it as the target), from the developer tools, scripts and automations.

| Action | What it displays |
|---|---|
| `shield_remote_center.send_toast` | A discreet pill at the top of the screen |
| `shield_remote_center.send_banner` | A wide pill at the top of the screen, for alerts |
| `shield_remote_center.send_popup` | A sheet at the bottom of the screen, optionally with a live camera / image |
| `shield_remote_center.send_camera` | A live camera view in the top right corner |
| `shield_remote_center.dismiss` | Closes the notifications (optionally of one `kind`) |

Fields of the four `send_*` actions:

| Field | Description |
|---|---|
| `primary` (required) | Main text |
| `secondary` | Second line |
| `icon` | Any Material Design icon, e.g. `mdi:door-open` |
| `color` | Accent color: `[r, g, b]`, `#RRGGBB` or a color name |
| `timeout` | Seconds before it disappears. `0` keeps it until a button or the Back key is pressed. Defaults: toast 8, banner 15, popup 30, camera 30 |
| `buttons` | Up to 4 buttons (see below) |
| `camera_entity` | `camera.*` or `image.*` entity shown live (required for `send_camera`, optional for `send_popup`) |
| `refresh_rate` | Seconds between two pictures of the live view (0.5 to 10, default 1) |

Each button: `label` (required), `action` (optional list of actions to run when pressed, e.g. a script), `id` (optional, reported in the event, defaults to the position) and `dismiss` (default `true`; set `false` to keep the notification open, e.g. to open a gate while still watching the camera).

A new notification replaces the previous one of the same kind.

### Examples

Popup when someone rings, with a live camera and a button running the script which opens the gate:

```yaml
action: shield_remote_center.send_popup
target:
  entity_id: media_player.shield_192_168_1_20_8765
data:
  primary: Someone is at the gate
  secondary: Open it from the TV?
  icon: mdi:bell-ring
  camera_entity: camera.gate
  timeout: 30
  buttons:
    - label: Open the gate
      action:
        - action: script.open_gate
    - label: Ignore
```

Camera view whose button keeps the view open:

```yaml
action: shield_remote_center.send_camera
target:
  entity_id: media_player.shield_192_168_1_20_8765
data:
  primary: Entrance
  secondary: Motion detected
  camera_entity: camera.entrance
  buttons:
    - label: Open the gate
      dismiss: false
      action:
        - action: script.open_gate
    - label: Close
```

Critical banner that stays until acknowledged:

```yaml
action: shield_remote_center.send_banner
target:
  entity_id: media_player.shield_192_168_1_20_8765
data:
  primary: Smoke detected
  secondary: Kitchen
  icon: mdi:smoke-detector-alert
  color: "#FF453A"
  timeout: 0
```

### Events

Button presses and dismissals are also fired on the event bus as `shield_remote_center_event`, to use in automations (for example when a button has no `action`):

```yaml
trigger:
  - trigger: event
    event_type: shield_remote_center_event
    event_data:
      type: button_pressed
      button_id: open
```

Event data: `entity_id`, `type` (`button_pressed` or `dismissed`), `kind`, `notification_id`, and `button_id`, `button_index`, `button_label` or `reason` (`timeout`, `back`, `button`, `replaced`, `api`).

## Using the remote control

Notifications without buttons never take the focus nor the touches. A notification **with buttons takes the focus** so that it can be operated with the D-pad (OK presses the highlighted button) and closed with Back: the remote does not control the playing app while it is displayed.

## Protocol

WebSocket `ws://<host>:<port>/ws` with header `Authorization: Bearer <token>`; JSON text frames, plus binary frames for pictures.

- App → HA, on connection then on each change: `{"type":"state","state":"playing|paused|idle","app_id":"...","title":"..."|null,"duration_ms":123|null,"position_ms":123|null,"position_updated_at":"2026-10-16T13:00:00.000Z","supported_actions":["play","pause","stop","seek","next","previous"]}`
- HA → app: `{"id":1,"type":"command","command":"play|pause|play_pause|stop|next|previous|seek","position_ms":120000}`
- HA → app: `{"id":2,"type":"notify","notification_id":42,"kind":"toast|banner|popup|camera","primary":"...","secondary":"...","icon":"mdi:bell","color":"#0A84FF","timeout":8,"buttons":[{"id":"0","label":"OK","dismiss":true}],"has_media":false}`
- HA → app: `{"id":3,"type":"dismiss","kind":"toast"}` (all kinds if omitted)
- HA → app, binary: 4 bytes big-endian `notification_id` followed by a JPEG or PNG picture.
- App → HA: `{"id":1,"type":"result","ok":true}` or `{"id":1,"type":"result","ok":false,"error":"no_session|unsupported|unknown_command|invalid_position|invalid_message|overlay_disabled|overlay_permission|overlay_failed|failed"}`
- App → HA: `{"type":"event","event":"button","notification_id":42,"button_id":"0"}` and `{"type":"event","event":"dismissed","notification_id":42,"reason":"timeout"}`

## Known limitations

- Some applications report no title (e.g. Netflix) or an unreliable position (e.g. Prime Video); the integration shows what the application declares.
- A playback command is only available if the application declares the matching action.
- The live view is a series of pictures taken by Home Assistant (about 1 per second by default), not a video stream.
- Seek is not validated on the apps yet.
