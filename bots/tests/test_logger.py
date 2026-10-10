import logging

from bots.logger import logger as logger_module
from bots.logger.logger import get_logger


def test_each_name_gets_a_file_of_its_own():
    get_logger("pricer_BTCUSDT").info("from the pricer")
    get_logger("lp_BTCUSDT").info("from the lp")

    pricer = (logger_module.LOG_DIR / "pricer_BTCUSDT.log").read_text()
    lp = (logger_module.LOG_DIR / "lp_BTCUSDT.log").read_text()
    assert "from the pricer" in pricer and "from the lp" not in pricer
    assert "from the lp" in lp and "from the pricer" not in lp


def test_asking_twice_does_not_print_every_line_twice():
    """logging.getLogger returns one object per name; a second pair of handlers doubles every line."""
    first = get_logger("twice")
    second = get_logger("twice")
    second.info("once")

    assert first is second
    assert len(first.handlers) == 2
    assert (logger_module.LOG_DIR / "twice.log").read_text().count("once") == 1


def test_a_restart_keeps_the_previous_runs_log():
    """The run before a crash is the one worth reading."""
    logger = get_logger("restarted")
    logger.info("first run")
    for handler in list(logger.handlers):
        handler.close()
        logger.removeHandler(handler)

    get_logger("restarted").info("second run")

    text = (logger_module.LOG_DIR / "restarted.log").read_text()
    assert "first run" in text and "second run" in text


def test_it_does_not_echo_through_the_root_logger():
    assert get_logger("quiet").propagate is False
    assert isinstance(get_logger("quiet"), logging.Logger)
