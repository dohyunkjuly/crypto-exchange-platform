import logging

import pytest

from bots.logger import logger as logger_module


@pytest.fixture(autouse=True)
def logs_go_to_a_temporary_directory(tmp_path, monkeypatch):
    """A test must not write into data/logs, nor inherit handlers a previous test attached."""
    monkeypatch.setattr(logger_module, "LOG_DIR", tmp_path / "logs")
    before = set(logging.root.manager.loggerDict)
    yield
    for name in set(logging.root.manager.loggerDict) - before:
        created = logging.getLogger(name)
        for handler in list(created.handlers):
            handler.close()
            created.removeHandler(handler)
