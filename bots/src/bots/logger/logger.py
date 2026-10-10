"""One log file per name: a controller asks for a logger by its own name and gets its own file.

`get_logger("trade_replay_BTCUSDT")` writes that name `.log` and the console. A dozen
controllers in one process interleave on the console and stay apart on disk, which is the
point: what one of them did is one file, read top to bottom.
"""

import logging
import os
from pathlib import Path

__all__ = ["get_logger", "LOG_DIR"]

# bots/data/logs, wherever the process was started from. BOTS_LOG_DIR moves it.
LOG_DIR = Path(os.getenv("BOTS_LOG_DIR") or Path(__file__).resolve().parents[3] / "data" / "logs")

_FORMAT = "%(asctime)s - [%(levelname)s] - %(name)s - %(message)s - %(filename)s:%(lineno)d"


def get_logger(name: str, level: int = logging.INFO) -> logging.Logger:
    logger = logging.getLogger(name)
    # logging.getLogger returns the same object for the same name, so asking twice would
    # otherwise attach a second pair of handlers and print every line twice.
    if logger.handlers:
        return logger

    logger.setLevel(level)
    # Its own handlers are the whole output. Left on, the root logger would print it again.
    logger.propagate = False

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    formatter = logging.Formatter(_FORMAT)

    # Appended to, never truncated and never rotated away: a controller's log is its whole
    # history, and the run before a crash is the one worth reading. Nothing here deletes it.
    to_file = logging.FileHandler(LOG_DIR / f"{name}.log", mode="a", encoding="utf-8")
    to_file.setFormatter(formatter)
    logger.addHandler(to_file)

    to_console = logging.StreamHandler()
    to_console.setFormatter(formatter)
    logger.addHandler(to_console)
    return logger
