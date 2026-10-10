"""Replays the reference feed's trades onto our markets: one controller per market.

    bots-trade-replay
    bots-trade-replay --symbols BTCUSDT,ETHUSDT --seconds 60

With no --symbols it runs every market under [trade_replay] in config/bots.toml. Each
market has two accounts of its own, one keeping the bids and one the asks, and one feed stream.
A market's own `[trade_replay.BTCUSDT]` table overrides the sizes for that market.
"""

import asyncio
import sys

from bots.clients.reference_feed_client import ReferenceFeedClient
from bots.clients.spot_client import SpotAsyncClient
from bots.controllers.controller_runner import main_arguments, run_controllers
from bots.controllers.trade_replay_controller import (
    SimulatorConfig,
    TradeReplayController,
)
from bots.settings.settings import Settings

CONTROLLER = "trade_replay"


async def main_async() -> int:
    args = main_arguments(__doc__.splitlines()[0])
    settings = Settings.load(args.config)
    symbols = args.symbols.split(",") if args.symbols else settings.controller(CONTROLLER).get("symbols", [])
    if not symbols:
        raise SystemExit(f"no markets: pass --symbols, or list them under [{CONTROLLER}] in the config")

    table = settings.controller(CONTROLLER)
    pairs = settings.accounts_for(CONTROLLER, symbols)

    spots = {account: await SpotAsyncClient.create(account.api_key, account.api_secret,
                                                   api_url=settings.spot_api_url)
             for pair in pairs.values() for account in pair}
    feed = await ReferenceFeedClient.create(rest_url=settings.feed_rest_url,
                                              stream_url=settings.feed_stream_url)
    try:
        controllers = [
            TradeReplayController(symbol, spots[pairs[symbol][0]], spots[pairs[symbol][1]],
                                            feed, SimulatorConfig.from_table(table, symbol))
            for symbol in symbols]
        await run_controllers(controllers, seconds=args.seconds)
    finally:
        await asyncio.gather(*(spot.close() for spot in spots.values()), feed.close())
    return 0


def main() -> int:
    try:
        return asyncio.run(main_async())
    except (ValueError, FileNotFoundError) as e:
        # A mistake in the config or the accounts file: say what, without a traceback.
        raise SystemExit(f"{CONTROLLER}: {e}") from None


if __name__ == "__main__":
    sys.exit(main())
