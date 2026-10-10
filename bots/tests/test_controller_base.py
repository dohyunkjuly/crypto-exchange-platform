import asyncio
import json

import pytest
import websockets

from bots.controllers import controller_runner
from bots.controllers.controller_base import ControllerBase
from bots.controllers.controller_runner import run_controllers


class Recorder(ControllerBase):
    tick_seconds = 0.01
    retry_seconds = 0.01

    def __init__(self, name="recorder", *, fail_on_tick=None, fail_on_start=False):
        super().__init__(name)
        self.events: list[str] = []
        self._fail_on_tick = fail_on_tick
        self._fail_on_start = fail_on_start
        self._ticks = 0

    async def _on_start(self):
        self.events.append("start")
        if self._fail_on_start:
            raise RuntimeError("cannot start")

    async def _on_tick(self):
        self._ticks += 1
        if self._ticks == self._fail_on_tick:
            raise RuntimeError("bad tick")
        self.events.append("tick")

    async def _on_stop(self):
        self.events.append("stop")


async def run_for(controller, seconds=0.06):
    task = asyncio.create_task(controller.run())
    await asyncio.sleep(seconds)
    controller.stop()
    await asyncio.wait_for(task, timeout=1)


class TestLifecycle:
    async def test_it_starts_ticks_and_stops_in_that_order(self):
        controller = Recorder()
        await run_for(controller)

        assert controller.events[0] == "start"
        assert controller.events[-1] == "stop"
        assert controller.events.count("tick") >= 2

    async def test_a_failed_tick_is_logged_and_the_next_one_still_runs(self):
        controller = Recorder(fail_on_tick=2)
        await run_for(controller)

        assert controller.events.count("tick") >= 2
        assert controller.events[-1] == "stop"

    async def test_a_controller_that_cannot_start_still_stops_cleanly(self):
        """_on_stop is where resting orders are cancelled. It must run however the run ended."""
        controller = Recorder(fail_on_start=True)
        await asyncio.wait_for(controller.run(), timeout=1)

        assert controller.events == ["start", "stop"]

    async def test_cancelling_it_still_runs_on_stop(self):
        controller = Recorder()
        task = asyncio.create_task(controller.run())
        await asyncio.sleep(0.03)
        task.cancel()
        await asyncio.wait_for(task, timeout=1)

        assert controller.events[-1] == "stop"

    async def test_stop_wakes_a_sleeping_controller_at_once(self):
        controller = Recorder()
        controller.tick_seconds = 60
        task = asyncio.create_task(controller.run())
        await asyncio.sleep(0.03)
        controller.stop()

        await asyncio.wait_for(task, timeout=0.5)


class TestRunner:
    async def test_every_controller_runs_and_every_one_is_stopped(self):
        controllers = [Recorder("a"), Recorder("b"), Recorder("c")]
        await asyncio.wait_for(run_controllers(controllers, seconds=0.05), timeout=2)

        for controller in controllers:
            assert controller.events[0] == "start" and controller.events[-1] == "stop"

    async def test_one_controller_dying_does_not_end_the_others(self):
        healthy, sick = Recorder("healthy"), Recorder("sick", fail_on_start=True)
        await asyncio.wait_for(run_controllers([healthy, sick], seconds=0.05), timeout=2)

        assert healthy.events.count("tick") >= 2
        assert sick.events == ["start", "stop"]

    async def test_a_controller_that_never_stops_is_reported_and_the_run_ends(self, tmp_path, monkeypatch):
        class Stuck(Recorder):
            async def _on_stop(self):
                await asyncio.Event().wait()

        monkeypatch.setattr(controller_runner, "STOP_SECONDS", 0.1)
        healthy = Recorder("healthy")
        await asyncio.wait_for(run_controllers([Stuck("stuck"), healthy], seconds=0.05), timeout=8)

        assert healthy.events[-1] == "stop"
        assert "did not stop within" in (tmp_path / "logs" / "stuck.log").read_text()

    async def test_running_nothing_is_an_error_not_a_silent_exit(self):
        with pytest.raises(ValueError):
            await run_controllers([])


class FakeSocket:
    """Yields its frames, then drops the way a real connection does."""

    def __init__(self, frames):
        self._frames = list(frames)
        self.closed = False

    async def recv(self):
        if self._frames:
            return self._frames.pop(0)
        raise websockets.ConnectionClosedOK(None, None)

    async def close(self):
        self.closed = True


class Listener(ControllerBase):
    retry_seconds = 0.01

    def __init__(self, sockets, *, choke_on=None):
        super().__init__("listener")
        self._sockets = list(sockets)
        self._choke_on = choke_on
        self.opened: list[FakeSocket] = []
        self.connects = 0
        self.heard: list[dict] = []

    async def _on_start(self): ...
    async def _on_stop(self): ...

    async def _run(self):
        await self._consume(self._open, self._on_message, on_connect=self._on_connect)

    async def _open(self):
        if not self._sockets:
            self.stop()
            raise websockets.ConnectionClosedOK(None, None)
        self.opened.append(self._sockets.pop(0))
        return self.opened[-1]

    async def _on_connect(self):
        self.connects += 1

    async def _on_message(self, frame):
        if frame == self._choke_on:
            raise RuntimeError("cannot handle this one")
        self.heard.append(frame)


class TestConsume:
    async def test_a_dropped_socket_is_reopened_and_on_connect_runs_each_time(self):
        """Nothing is replayed after a reconnect, so on_connect is where state gets re-read."""
        first = FakeSocket([json.dumps({"n": 1})])
        second = FakeSocket([json.dumps({"n": 2})])
        listener = Listener([first, second])
        await asyncio.wait_for(listener.run(), timeout=2)

        assert listener.heard == [{"n": 1}, {"n": 2}]
        assert listener.connects == 2
        assert first.closed and second.closed

    async def test_a_frame_it_cannot_handle_does_not_cost_it_the_socket(self):
        socket = FakeSocket([json.dumps({"n": 1}), json.dumps({"bad": True}), json.dumps({"n": 3})])
        listener = Listener([socket], choke_on={"bad": True})
        await asyncio.wait_for(listener.run(), timeout=2)

        assert listener.heard == [{"n": 1}, {"n": 3}]
        assert len(listener.opened) == 1, "one bad frame must not force a reconnect"
