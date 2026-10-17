"""Toasts, banners, popups and cameras displayed on the Shield."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import logging
import re
import time
from typing import Any

import voluptuous as vol

from homeassistant.components.camera import async_get_image as async_get_camera_image
from homeassistant.components.camera import async_request_stream
from homeassistant.components.image import async_get_image as async_get_image_entity
from homeassistant.core import Context, HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.entity import Entity
from homeassistant.helpers.network import NoURLAvailableError, get_url
from homeassistant.helpers.script import Script
from homeassistant.util import color as color_util

from .client import ShieldClient, ShieldCommandError, ShieldConnectionError
from .const import (
    ATTR_BUTTONS,
    ATTR_CAMERA_ENTITY,
    ATTR_COLOR,
    ATTR_ICON,
    ATTR_KIND,
    ATTR_LIVE,
    ATTR_PRIMARY,
    ATTR_REFRESH_RATE,
    ATTR_SECONDARY,
    ATTR_TIMEOUT,
    DEFAULT_REFRESH_RATE,
    DEFAULT_TIMEOUTS,
    DOMAIN,
    EVENT_NOTIFICATION,
    KIND_CAMERA,
    KIND_POPUP,
    KINDS,
    MAX_STREAM_SECONDS,
)

_LOGGER = logging.getLogger(__name__)

HEX_COLOR = re.compile(r"^#[0-9a-fA-F]{6}$")
MAX_BUTTONS = 4


def validate_color(value: Any) -> str:
    """Accept '#RRGGBB', a CSS color name or an [r, g, b] list, return '#RRGGBB'."""
    if isinstance(value, str):
        if HEX_COLOR.match(value):
            return value.upper()
        try:
            rgb = tuple(color_util.color_name_to_rgb(value))
        except ValueError as err:
            raise vol.Invalid(f"Invalid color: {value}") from err
    elif isinstance(value, (list, tuple)) and len(value) == 3:
        try:
            rgb = tuple(int(channel) for channel in value)
        except (TypeError, ValueError) as err:
            raise vol.Invalid(f"Invalid color: {value}") from err
        if any(not 0 <= channel <= 255 for channel in rgb):
            raise vol.Invalid(f"Invalid color: {value}")
    else:
        raise vol.Invalid(f"Invalid color: {value}")
    return "#{:02X}{:02X}{:02X}".format(*rgb)


BUTTON_SCHEMA = vol.Schema(
    {
        vol.Required("label"): cv.string,
        # Reported in the event, to recognize the button in an automation (default: its position)
        vol.Optional("id"): cv.string,
        # Actions run when the button is pressed, e.g. a script
        vol.Optional("action"): cv.SCRIPT_SCHEMA,
        # Whether pressing the button closes the notification
        vol.Optional("dismiss", default=True): cv.boolean,
    }
)


# Simple form of the buttons: for each button, a label, a type of action to choose and an entity, instead of
# writing the actions. The fields have unique names (button_1_label...): the sections of the action editor only
# group them, the data is not nested.
BUTTON_COUNT = 3

# What the button does, besides closing the notification when "dismiss" is on
TYPE_CLOSE = "close"
TYPE_SCRIPT = "script"
TYPE_TOGGLE = "toggle"
TYPE_TURN_ON = "turn_on"
TYPE_TURN_OFF = "turn_off"
TYPE_AUTOMATION = "automation"
TYPE_ACTION = "action"
BUTTON_TYPES = (
    TYPE_CLOSE,
    TYPE_SCRIPT,
    TYPE_TOGGLE,
    TYPE_TURN_ON,
    TYPE_TURN_OFF,
    TYPE_AUTOMATION,
    TYPE_ACTION,
)

# Service called on the chosen entity by each type of button
PRESET_SERVICES = {
    TYPE_SCRIPT: "script.turn_on",
    TYPE_TOGGLE: "homeassistant.toggle",
    TYPE_TURN_ON: "homeassistant.turn_on",
    TYPE_TURN_OFF: "homeassistant.turn_off",
    TYPE_AUTOMATION: "automation.trigger",
}


def _simple_buttons_schema() -> dict[Any, Any]:
    """The fields of the simple buttons: button_1_label, button_1_type, button_1_entity..."""
    schema: dict[Any, Any] = {}
    for number in range(1, BUTTON_COUNT + 1):
        prefix = f"button_{number}_"
        # A button without label is not displayed
        schema[vol.Optional(prefix + "label")] = cv.string
        schema[vol.Optional(prefix + "type")] = vol.In(BUTTON_TYPES)
        schema[vol.Optional(prefix + "entity")] = cv.entity_id
        # Custom actions, for what the types do not cover
        schema[vol.Optional(prefix + "action")] = cv.SCRIPT_SCHEMA
        schema[vol.Optional(prefix + "dismiss", default=True)] = cv.boolean
    return schema

def button_actions(button: dict[str, Any]) -> list[dict[str, Any]]:
    """The actions to run when a button is pressed: its type and its custom actions."""
    actions: list[dict[str, Any]] = []
    entity = button.get("entity")
    button_type = button.get("type")
    if button_type is None and entity:
        # Not chosen: guess from the entity
        domain = entity.split(".")[0]
        button_type = (
            TYPE_SCRIPT
            if domain == "script"
            else TYPE_AUTOMATION if domain == "automation" else TYPE_TOGGLE
        )
    if button_type in PRESET_SERVICES and entity:
        step: dict[str, Any] = {
            "action": PRESET_SERVICES[button_type],
            "target": {"entity_id": entity},
        }
        if button_type == TYPE_AUTOMATION:
            step["data"] = {"skip_condition": True}
        actions.append(step)
    if custom := button.get("action"):
        actions.extend(custom)
    return actions


CLOSE_LABELS = {
    "fr": "Fermer",
    "de": "Schließen",
    "es": "Cerrar",
    "it": "Chiudi",
    "nl": "Sluiten",
    "pt": "Fechar",
}


def _schema(kind: str) -> dict[Any, Any]:
    schema: dict[Any, Any] = {
        vol.Required(ATTR_PRIMARY): cv.string,
        vol.Optional(ATTR_SECONDARY): cv.string,
        vol.Optional(ATTR_ICON): cv.icon,
        vol.Optional(ATTR_COLOR): validate_color,
        vol.Optional(ATTR_TIMEOUT, default=DEFAULT_TIMEOUTS[kind]): vol.All(
            vol.Coerce(float), vol.Range(min=0, max=3600)
        ),
        # Advanced form of the buttons, for YAML: a list of buttons
        vol.Optional(ATTR_BUTTONS, default=list): vol.All(cv.ensure_list, [BUTTON_SCHEMA]),
    }
    schema.update(_simple_buttons_schema())
    if kind in (KIND_POPUP, KIND_CAMERA):
        camera_key = (
            vol.Required(ATTR_CAMERA_ENTITY)
            if kind == KIND_CAMERA
            else vol.Optional(ATTR_CAMERA_ENTITY)
        )
        schema[camera_key] = cv.entity_domain(["camera", "image"])
        schema[vol.Optional(ATTR_REFRESH_RATE, default=DEFAULT_REFRESH_RATE)] = vol.All(
            vol.Coerce(float), vol.Range(min=0.5, max=10)
        )
        # Play the live video of the camera when it has a stream, instead of pictures
        schema[vol.Optional(ATTR_LIVE, default=True)] = cv.boolean
    return schema


SEND_SCHEMAS = {kind: _schema(kind) for kind in KINDS}
DISMISS_SCHEMA = {vol.Optional(ATTR_KIND): vol.In(KINDS)}

ERROR_MESSAGES = {
    "overlay_disabled": "Notifications are not allowed in the settings of the app on the Shield",
    "overlay_permission": "The app has no permission to display over other apps on the Shield",
    "overlay_failed": "The Shield could not display the notification",
    "invalid_message": "The Shield rejected the notification",
}


async def _async_get_image(hass: HomeAssistant, entity_id: str) -> bytes:
    """Get the current picture of a camera or image entity."""
    if entity_id.startswith("image."):
        return (await async_get_image_entity(hass, entity_id)).content
    return (await async_get_camera_image(hass, entity_id)).content


@dataclass
class _Active:
    """A notification currently displayed on the Shield."""

    notification_id: int
    kind: str
    context: Context | None
    # Per button: the id given by the user (or its position) and its label
    button_ids: list[str] = field(default_factory=list)
    button_labels: list[str] = field(default_factory=list)
    scripts: dict[int, Script] = field(default_factory=dict)
    stream: asyncio.Task | None = None
    # Live view, to send pictures again if the video stream fails
    camera_entity: str | None = None
    refresh_rate: float = 1.0
    duration: float = 0.0

    def cancel_stream(self) -> None:
        """Stop sending images."""
        if self.stream:
            self.stream.cancel()
            self.stream = None


def collect_buttons(kind: str, data: dict[str, Any], language: str) -> list[dict[str, Any]]:
    """Merge the 2 forms of buttons, and make sure a popup or a camera can be closed."""
    buttons: list[dict[str, Any]] = list(data.get(ATTR_BUTTONS, []))
    for number in range(1, BUTTON_COUNT + 1):
        prefix = f"button_{number}_"
        label = data.get(prefix + "label")
        if not label:
            continue
        button: dict[str, Any] = {"label": label, "dismiss": data.get(prefix + "dismiss", True)}
        for name in ("type", "entity", "action"):
            if (value := data.get(prefix + name)) is not None:
                button[name] = value
        if button.get("type") in PRESET_SERVICES and not button.get("entity"):
            raise ServiceValidationError(
                f"Button {number} needs an entity for the type {button['type']}"
            )
        buttons.append(button)
    # The same shape for both forms of buttons
    buttons = [{**button, "actions": button_actions(button)} for button in buttons]
    if len(buttons) > MAX_BUTTONS:
        raise HomeAssistantError(f"At most {MAX_BUTTONS} buttons are supported")
    if kind in (KIND_POPUP, KIND_CAMERA) and not any(
        button.get("dismiss", True) for button in buttons
    ):
        buttons.append(
            {
                "label": CLOSE_LABELS.get(language.split("-")[0].lower(), "Close"),
                "id": "close",
                "dismiss": True,
            }
        )
    return buttons


class NotificationManager:
    """Sends notifications to a Shield and handles the answers of the app."""

    def __init__(self, hass: HomeAssistant, client: ShieldClient, entity: Entity) -> None:
        self._hass = hass
        self._client = client
        self._entity = entity
        # Unique among the restarts of Home Assistant: the app may still display an old one
        self._next_id = int(time.time()) & 0x3FFFFFFF
        self._active: dict[str, _Active] = {}

    async def async_send(
        self, kind: str, data: dict[str, Any], context: Context | None
    ) -> None:
        """Display a notification. Raises HomeAssistantError if the Shield refuses it."""
        self._next_id += 1
        active = _Active(self._next_id, kind, context)
        buttons = []
        for index, button in enumerate(
            collect_buttons(kind, data, self._hass.config.language)
        ):
            active.button_ids.append(button.get("id") or str(index))
            active.button_labels.append(button["label"])
            buttons.append(
                {
                    "id": str(index),
                    "label": button["label"],
                    "dismiss": button.get("dismiss", True),
                }
            )
            if actions := button.get("actions"):
                active.scripts[index] = Script(
                    self._hass,
                    cv.SCRIPT_SCHEMA(actions),
                    f"{self._entity.entity_id} {kind} button {button['label']}",
                    DOMAIN,
                )

        camera_entity = data.get(ATTR_CAMERA_ENTITY)
        stream_url = None
        if camera_entity and data.get(ATTR_LIVE, True):
            stream_url = await self._async_stream_url(camera_entity)
        payload: dict[str, Any] = {
            "notification_id": active.notification_id,
            "kind": kind,
            "primary": data[ATTR_PRIMARY],
            "timeout": data[ATTR_TIMEOUT],
            "buttons": buttons,
            "has_media": camera_entity is not None,
        }
        if stream_url:
            payload["stream_url"] = stream_url
        for key in (ATTR_SECONDARY, ATTR_ICON, ATTR_COLOR):
            if key in data:
                payload[key] = data[key]

        previous = self._active.get(kind)
        # Registered before sending: the events of the app may come right after the result
        self._active[kind] = active
        try:
            await self._client.async_request("notify", **payload)
        except ShieldConnectionError as err:
            self._forget(active)
            raise HomeAssistantError("The Shield is unreachable") from err
        except ShieldCommandError as err:
            self._forget(active)
            code = str(err)
            raise HomeAssistantError(
                ERROR_MESSAGES.get(code, f"The Shield refused the notification: {code}")
            ) from err
        if previous:
            previous.cancel_stream()

        if camera_entity:
            active.camera_entity = camera_entity
            active.refresh_rate = data[ATTR_REFRESH_RATE]
            active.duration = (
                data[ATTR_TIMEOUT] + 2 if data[ATTR_TIMEOUT] else MAX_STREAM_SECONDS
            )
            # With a video stream, a single picture is shown while the video starts
            self._start_pictures(active, single=bool(stream_url))

    async def async_dismiss(self, kind: str | None) -> None:
        """Close the notifications of a kind, or all of them."""
        extra = {"kind": kind} if kind else {}
        try:
            await self._client.async_request("dismiss", **extra)
        except ShieldConnectionError as err:
            raise HomeAssistantError("The Shield is unreachable") from err

    @callback
    def async_reset(self) -> None:
        """Forget everything, e.g. when the Shield is gone."""
        for active in self._active.values():
            active.cancel_stream()
        self._active.clear()

    @callback
    def handle_event(self, data: dict[str, Any]) -> None:
        """Handle a button press or a dismissal reported by the app."""
        notification_id = data.get("notification_id")
        active = next(
            (a for a in self._active.values() if a.notification_id == notification_id),
            None,
        )
        if active is None:
            return
        event = data.get("event")
        if event == "button":
            self._handle_button(active, data)
        elif event == "dismissed":
            self._forget(active)
            self._fire(active, "dismissed", reason=data.get("reason"))
        elif event == "stream_failed":
            _LOGGER.debug("The Shield can not play the stream of %s", active.camera_entity)
            self._start_pictures(active, single=False)

    def _handle_button(self, active: _Active, data: dict[str, Any]) -> None:
        try:
            index = int(data.get("button_id", -1))
            button_id = active.button_ids[index]
        except (ValueError, IndexError):
            return
        self._fire(
            active,
            "button_pressed",
            button_id=button_id,
            button_index=index,
            button_label=active.button_labels[index],
        )
        if script := active.scripts.get(index):
            self._hass.async_create_task(script.async_run(context=active.context))

    def _fire(self, active: _Active, event_type: str, **extra: Any) -> None:
        self._hass.bus.async_fire(
            EVENT_NOTIFICATION,
            {
                "entity_id": self._entity.entity_id,
                "type": event_type,
                "kind": active.kind,
                "notification_id": active.notification_id,
                **extra,
            },
            context=active.context,
        )

    def _forget(self, active: _Active) -> None:
        active.cancel_stream()
        if self._active.get(active.kind) is active:
            del self._active[active.kind]

    async def _async_stream_url(self, entity_id: str) -> str | None:
        """The HLS stream of a camera, served by Home Assistant, or None if there is none."""
        if not entity_id.startswith("camera."):
            return None
        try:
            path = await async_request_stream(self._hass, entity_id, "hls")
            # The Shield reaches Home Assistant on the local network
            base = get_url(
                self._hass, allow_external=False, allow_cloud=False, prefer_external=False
            )
        except (HomeAssistantError, NoURLAvailableError) as err:
            _LOGGER.debug("No live stream for %s: %s", entity_id, err)
            return None
        return f"{base}{path}"

    def _start_pictures(self, active: _Active, single: bool) -> None:
        """Send the pictures of the camera: only one, or as a live view until the end."""
        if active.camera_entity is None or (active.stream and not active.stream.done()):
            return
        active.stream = self._hass.async_create_background_task(
            self._stream(
                active,
                active.camera_entity,
                active.refresh_rate,
                0 if single else active.duration,
            ),
            f"{DOMAIN} pictures {active.camera_entity}",
        )

    async def _stream(
        self, active: _Active, entity_id: str, refresh_rate: float, duration: float
    ) -> None:
        """Send the pictures of a camera to the notification. duration 0: a single picture."""
        loop = asyncio.get_running_loop()
        end = loop.time() + duration
        while True:
            started = loop.time()
            try:
                image = await _async_get_image(self._hass, entity_id)
                await self._client.async_send_frame(active.notification_id, image)
            except ShieldConnectionError:
                return
            except (HomeAssistantError, TimeoutError) as err:
                # The camera may be temporarily unavailable: try again
                _LOGGER.debug("No image from %s: %s", entity_id, err)
            if loop.time() >= end:
                return
            await asyncio.sleep(max(0.1, refresh_rate - (loop.time() - started)))
