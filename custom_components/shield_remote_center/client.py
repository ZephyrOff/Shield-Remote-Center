"""WebSocket client for the local API served by the Android app."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
import contextlib
import logging
import struct
from typing import Any

import aiohttp

from .const import WS_PATH

_LOGGER = logging.getLogger(__name__)

CONNECT_TIMEOUT = 10
COMMAND_TIMEOUT = 5
HEARTBEAT = 30
BACKOFF_MIN = 1
BACKOFF_MAX = 30


class ShieldAuthError(Exception):
    """The server refused the handshake (wrong token)."""


class ShieldConnectionError(Exception):
    """The server is unreachable."""


class ShieldCommandError(Exception):
    """The app refused or failed a request."""


def _headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def async_validate(
    session: aiohttp.ClientSession, host: str, port: int, token: str
) -> None:
    """Connect once to validate the settings."""
    url = f"ws://{host}:{port}{WS_PATH}"
    try:
        async with asyncio.timeout(CONNECT_TIMEOUT):
            ws = await session.ws_connect(url, headers=_headers(token))
            await ws.close()
    except aiohttp.WSServerHandshakeError as err:
        raise ShieldAuthError from err
    except (aiohttp.ClientError, TimeoutError, OSError) as err:
        raise ShieldConnectionError from err


class ShieldClient:
    """Keeps a connection to the app, with reconnection and backoff."""

    def __init__(
        self, session: aiohttp.ClientSession, host: str, port: int, token: str
    ) -> None:
        self._session = session
        self._url = f"ws://{host}:{port}{WS_PATH}"
        self._token = token
        self._task: asyncio.Task | None = None
        self._ws: aiohttp.ClientWebSocketResponse | None = None
        self._next_id = 0
        self._pending: dict[int, asyncio.Future[dict[str, Any]]] = {}
        self._listeners: list[Callable[[], None]] = []
        self._event_listeners: list[Callable[[dict[str, Any]], None]] = []
        self.state: dict[str, Any] | None = None

    @property
    def available(self) -> bool:
        """Whether connected and a state snapshot was received."""
        return self._ws is not None and not self._ws.closed and self.state is not None

    def add_listener(self, listener: Callable[[], None]) -> Callable[[], None]:
        """Register a callback for state/availability changes."""
        self._listeners.append(listener)
        return lambda: self._listeners.remove(listener)

    def add_event_listener(
        self, listener: Callable[[dict[str, Any]], None]
    ) -> Callable[[], None]:
        """Register a callback for the events sent by the app (button presses...)."""
        self._event_listeners.append(listener)
        return lambda: self._event_listeners.remove(listener)

    def _notify(self) -> None:
        for listener in list(self._listeners):
            listener()

    def start(self) -> None:
        """Start the connection loop."""
        self._task = asyncio.create_task(self._run())

    async def stop(self) -> None:
        """Stop the connection loop."""
        if self._task:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None

    async def _run(self) -> None:
        backoff = BACKOFF_MIN
        while True:
            try:
                async with self._session.ws_connect(
                    self._url, headers=_headers(self._token), heartbeat=HEARTBEAT
                ) as ws:
                    self._ws = ws
                    backoff = BACKOFF_MIN
                    _LOGGER.debug("Connected to %s", self._url)
                    async for msg in ws:
                        if msg.type == aiohttp.WSMsgType.TEXT:
                            self._handle(msg.json())
                        elif msg.type in (
                            aiohttp.WSMsgType.CLOSED,
                            aiohttp.WSMsgType.ERROR,
                        ):
                            break
            except asyncio.CancelledError:
                raise
            except aiohttp.WSServerHandshakeError:
                _LOGGER.warning("The Shield refused the connection (invalid token?)")
            except (aiohttp.ClientError, TimeoutError, OSError, ValueError) as err:
                _LOGGER.debug("Connection to %s lost: %s", self._url, err)
            self._ws = None
            self.state = None
            for future in self._pending.values():
                if not future.done():
                    future.set_exception(ShieldConnectionError())
            self._pending.clear()
            self._notify()
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, BACKOFF_MAX)

    def _handle(self, data: dict[str, Any]) -> None:
        message_type = data.get("type")
        if message_type == "state":
            self.state = data
            self._notify()
        elif message_type == "result":
            future = self._pending.pop(data.get("id"), None)
            if future and not future.done():
                future.set_result(data)
        elif message_type == "event":
            for listener in list(self._event_listeners):
                listener(data)

    async def async_request(self, message_type: str, **kwargs: Any) -> None:
        """Send a message of the given type and wait for its result."""
        if self._ws is None or self._ws.closed:
            raise ShieldConnectionError
        self._next_id += 1
        request_id = self._next_id
        future: asyncio.Future[dict[str, Any]] = (
            asyncio.get_running_loop().create_future()
        )
        self._pending[request_id] = future
        try:
            await self._ws.send_json({"id": request_id, "type": message_type, **kwargs})
            async with asyncio.timeout(COMMAND_TIMEOUT):
                result = await future
        except TimeoutError as err:
            raise ShieldCommandError("timeout") from err
        finally:
            self._pending.pop(request_id, None)
        if not result.get("ok"):
            raise ShieldCommandError(result.get("error", "unknown"))

    async def async_command(self, command: str, **kwargs: Any) -> None:
        """Send a playback command and wait for its result."""
        await self.async_request("command", command=command, **kwargs)

    async def async_send_frame(self, notification_id: int, image: bytes) -> None:
        """Send an image to display in the notification (4 bytes id + image)."""
        if self._ws is None or self._ws.closed:
            raise ShieldConnectionError
        await self._ws.send_bytes(struct.pack(">I", notification_id) + image)
