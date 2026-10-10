"""Runs controllers together and stops them together, and the arguments their mains take.

One task per controller, gathered -- with the two things that are easy to leave out: Ctrl-C
stops every controller the way a timer would, and each one's `_on_stop` is waited for, so a run
never ends with orders resting that nothing is watching.
"""

import argparse
import asyncio
import io
import resource
import signal
from collections.abc import Sequence
from pathlib import Path

from .controller_base import ControllerBase

__all__ = ["run_controllers", "main_arguments"]

# Long enough to cancel ten thousand orders on a slow exchange.
STOP_SECONDS = 120.0


async def run_controllers(controllers: Sequence[ControllerBase], seconds: float | None = None) -> None:
    """Until Ctrl-C, SIGTERM, `seconds` have passed, or every controller has ended by itself."""
    if not controllers:
        raise ValueError("no controllers to run")
    _raise_open_file_limit()

    stopping = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stopping.set)
        except NotImplementedError:  # pragma: no cover - Windows
            pass

    tasks = [asyncio.create_task(c.run(), name=c.name) for c in controllers]
    finished = asyncio.ensure_future(asyncio.gather(*tasks))
    wake = asyncio.create_task(stopping.wait())
    try:
        await asyncio.wait({finished, wake}, timeout=seconds, return_when=asyncio.FIRST_COMPLETED)
    finally:
        wake.cancel()
        # Asked, not cancelled: a controller finishes its step and runs _on_stop. `run` never
        # raises, so this gather only waits.
        for controller in controllers:
            controller.stop()
        await _wait_for_stop(dict(zip(tasks, controllers, strict=True)))


async def _wait_for_stop(tasks: dict[asyncio.Task, ControllerBase]) -> None:
    """A controller that has not stopped by now never will, and a bot that cannot be stopped gets
    killed with its orders still resting. Says where each one is stuck, then cancels it."""
    seconds = STOP_SECONDS
    _, stuck = await asyncio.wait(tasks, timeout=seconds)
    for task in stuck:
        frames = io.StringIO()
        task.print_stack(file=frames)
        tasks[task].logger.error("%s did not stop within %.0f s; cancelling it. It was at:\n%s",
                                 task.get_name(), seconds, frames.getvalue())
        task.cancel()
    if stuck:
        await asyncio.wait(stuck, timeout=5)


def _raise_open_file_limit(wanted: int = 4096) -> None:
    """A socket is an open file, and macOS gives a process 256 of them: a hundred and forty
    accounts with two connections each is already past that."""
    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    if soft < wanted:
        resource.setrlimit(resource.RLIMIT_NOFILE, (wanted if hard == resource.RLIM_INFINITY
                                                    else min(wanted, hard), hard))


def main_arguments(description: str) -> argparse.Namespace:
    """What every controller's main takes."""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--config", type=Path, default=None, help="default: config/bots.toml")
    parser.add_argument("--symbols", default=None, help="comma-separated; default: the config's")
    parser.add_argument("--seconds", type=float, default=None,
                        help="stop after this long; default: run until Ctrl-C")
    return parser.parse_args()
