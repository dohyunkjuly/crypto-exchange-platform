"""What every controller is: a name, a log file of its own, and a life -- start, run, stop.

A controller is one long-running job, such as the trade simulator for one market. Each is an
asyncio task, so a dozen of them share a process without sharing a loop, and a slow one delays
itself rather than the rest. ``controller_runner.py`` starts them and stops them together.

A subclass writes ``_on_start``, ``_on_stop`` and one of:

* ``_on_tick``  for work done on a timer. The default ``_run`` calls it every ``tick_seconds``.
* ``_run``      for work driven by a socket. ``_consume`` reads one for as long as the
                controller runs, reconnecting when it drops.

Clients are handed in, never built here: whoever builds a controller decides which accounts it
trades and which sessions it shares.
"""

import asyncio
import json
import traceback
from abc import ABC, abstractmethod
from collections.abc import Awaitable, Callable
from typing import Any

import websockets

from ..logger.logger import get_logger

__all__ = ["ControllerBase"]


class ControllerBase(ABC):

    # How often _on_tick runs, for a controller that works on a timer.
    tick_seconds: float = 1.0
    # After a dropped socket or a failed tick. Short: the market does not wait.
    retry_seconds: float = 1.0

    def __init__(self, name: str):
        self.name = name
        self.logger = get_logger(name)
        self._stopping = asyncio.Event()

    @property
    def stopping(self) -> bool:
        return self._stopping.is_set()

    def stop(self) -> None:
        """Asks it to finish. ``run`` returns once the current step and ``_on_stop`` are done."""
        self._stopping.set()

    async def run(self) -> None:
        """The whole life of the controller. Never raises: one sick controller is not a dead run."""
        self.logger.info("%s starting", self.name)
        try:
            await self._on_start()
            await self._run()
        except asyncio.CancelledError:
            self.logger.info("%s cancelled", self.name)
        except Exception as e:
            self.logger.error("%s died: %s\n%s", self.name, e, traceback.format_exc())
        finally:
            # Shielded: a second Ctrl-C must not interrupt the cancel-everything on the way out
            # and leave orders resting that nothing is watching.
            try:
                await asyncio.shield(self._on_stop())
            except Exception as e:
                self.logger.error("%s failed to stop cleanly: %s\n%s", self.name, e, traceback.format_exc())
            self.logger.info("%s stopped", self.name)

    # ------------------------------------------------------------------ for the subclass

    @abstractmethod
    async def _on_start(self) -> None:
        """Once, before anything else: read what it needs, clear what a previous run left."""

    async def _on_tick(self) -> None:  # noqa: B027 - optional: a socket-driven one overrides _run
        """One step of a timer-driven controller. Raising is logged and the next tick still runs."""

    @abstractmethod
    async def _on_stop(self) -> None:
        """Once, on the way out, however it ended. Leave nothing resting."""

    async def _run(self) -> None:
        """Ticks until stopped. Override for a controller driven by a socket instead."""
        while not self.stopping:
            try:
                await self._on_tick()
            except asyncio.CancelledError:
                raise
            except Exception as e:
                self.logger.error("%s tick failed: %s\n%s", self.name, e, traceback.format_exc())
                await self._sleep(self.retry_seconds)
            await self._sleep(self.tick_seconds)

    # ------------------------------------------------------------------ helpers

    async def _cancel_everything(self, client, symbol: str | None = None) -> int:
        """Cancels what this account has open, and says how many that was.

        For both ends of a run. On the way in: what a previous run left is priced off a market
        that has moved on. On the way out: quotes nothing is refreshing are not liquidity, just
        an order somebody will eventually take. Cancelling is never gated, so this works on an
        account that may no longer trade.
        """
        listed = await client.get_open_orders(symbol)
        if listed:
            await client.cancel_all_orders(symbol)
        return len(listed)

    async def _sleep(self, seconds: float) -> None:
        """Sleeps, but wakes at once when asked to stop."""
        try:
            await asyncio.wait_for(self._stopping.wait(), timeout=seconds)
        except TimeoutError:
            pass

    async def _consume(self, open_ws: Callable[[], Awaitable[Any]],
                       on_message: Callable[[dict], Awaitable[None]],
                       on_connect: Callable[[], Awaitable[None]] | None = None) -> None:
        """Reads one socket until stopped, reopening it whenever it drops.

        ``open_ws`` is a client's ``ws_*`` call, e.g. ``lambda: client.ws_trade("BTCUSDT")``.
        ``on_connect`` runs after every (re)connection and before any frame: nothing is
        replayed after a reconnect, so this is where state is re-read over REST.
        """
        while not self.stopping:
            ws = None
            try:
                ws = await open_ws()
                if on_connect is not None:
                    await on_connect()
                stop = asyncio.create_task(self._stopping.wait())
                receive = None
                try:
                    while not self.stopping:
                        receive = asyncio.create_task(ws.recv())
                        done, _ = await asyncio.wait({receive, stop}, return_when=asyncio.FIRST_COMPLETED)
                        if receive not in done:
                            receive.cancel()
                            break
                        # Outside the try below: a closed socket raises here, and that has
                        # to reach the reconnect, not be mistaken for a frame that went wrong.
                        message = receive.result()
                        try:
                            await on_message(json.loads(message))
                        except asyncio.CancelledError:
                            raise
                        except Exception as e:
                            # One frame it could not handle is not a reason to drop the socket.
                            self.logger.error("%s could not handle a frame: %s\n%s",
                                              self.name, e, traceback.format_exc())
                finally:
                    stop.cancel()
                    if receive is not None and not receive.done():
                        # Left pending, it fails when the socket closes and nobody is there to
                        # hear it: "Task exception was never retrieved" on every stop.
                        receive.cancel()
            except asyncio.CancelledError:
                raise
            except websockets.ConnectionClosed:
                self.logger.info("%s socket closed; reconnecting", self.name)
            except Exception as e:
                self.logger.error("%s socket failed: %s\n%s", self.name, e, traceback.format_exc())
            finally:
                if ws is not None:
                    await ws.close()
            if not self.stopping:
                await self._sleep(self.retry_seconds)
