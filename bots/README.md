# bots

Bots that trade on the exchange for testing purposes.

## Run it

The local stack must be up:

```bash
docker compose --profile full up -d
```

Install once:

```bash
cd bots
python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"
```

## Start the CLI

```bash
cd bots
.venv/bin/bots
```

It shows whether the stack, the accounts and the markets are fine, and a menu:

| Key | What it does |
| --- | --- |
| 1 | Check everything: places one test order far from the price, and cancels it |
| 2 | Show the markets |
| 3 | Create the bot accounts, funded, and write their API keys to `config/accounts.json` |
| 4 | Cancel every open order of the bot accounts |
| 5 | Run the trade simulator |
| 6 | Change the simulator's settings |

On a fresh stack, create the accounts first.

## The trade simulator

The one bot. It follows a public market's trades and repeats them on ours, so every market has a
moving price, a full book and a trade history. Each market gets two bot accounts:

- **The bids account** keeps random buy orders below the price; **the asks account** keeps as many
  sell orders above it. Both sides are topped back up as orders fill.
- **When the price moves onto resting orders,** they are cancelled rather than hit, so the book
  follows the reference price instead of resisting it.
- **For each reference trade,** one account rests a small order at that price and the other takes
  it. Whichever holds more of the base asset sells, so neither runs out.

It runs from the menu, or on its own:

```bash
.venv/bin/bots-trade-replay
.venv/bin/bots-trade-replay --symbols BTCUSDT,ETHUSDT --seconds 60
```

| Flag | Default | Meaning |
| --- | --- | --- |
| `--symbols` | the config's `symbols` | Comma-separated markets to run |
| `--seconds` | run until Ctrl-C | Stop after this long |
| `--config` | `config/bots.toml` | Another settings file |

Ctrl-C stops it and cancels what it has resting. Logs go to `data/logs/`, one file per market.

## Settings

All in `config/bots.toml`. Money is a quoted string, never a bare number.

`[trade_replay]`, the simulator:

| Key | Value | Meaning |
| --- | --- | --- |
| `symbols` | 12 USDT markets | Markets to run; each takes two bot accounts |
| `resting_orders_per_side` | `150` | Orders each account keeps on its side of the book |
| `liquidity_min_bps`, `liquidity_max_bps` | `"5"`, `"300"` | How far from the price a resting order sits; most land near the low end |
| `order_notional` | `"200"` | A resting order's typical size, in the quote asset |
| `trade_notional_min`, `trade_notional_max` | `"10"`, `"100"` | A replayed trade's size, in the quote asset; small, because both sides pay the fee |
| `replay_every_trade` | `false` | `true`: one trade for every reference trade. `false`: one every `trade_interval_seconds` |
| `trade_interval_seconds` | `"2"` | Seconds between trades when not replaying every one |
| `max_backlog` | `100` | Reference trades waiting to be replayed; past this the oldest are dropped |

A market's own values go in its own table and override the ones above, for example
`[trade_replay.BTCUSDT]` with `replay_every_trade = true`. Sizes are in that market's quote asset.

`[accounts]`, what menu option 3 creates:

| Key | Value | Meaning |
| --- | --- | --- |
| `count` | `150` | How many accounts |
| `label` | `"bot"` | Prefix of their emails |
| `fund` | `"1000000"` | Credited of every asset to each |
| `file` | `"config/accounts.json"` | Where their API keys are written |

`[spot]` and `[reference_feed]` are the addresses: spot-open-api for trading, web-bff for creating
and funding accounts in the dev profile, and the public market the simulator follows.

Any of these is overridden by an environment variable, without editing the file:
`BOTS_CONFIG`, `BOTS_SPOT_API_URL`, `BOTS_SPOT_DEV_URL`, `BOTS_FEED_REST_URL`,
`BOTS_FEED_STREAM_URL`, `BOTS_ACCOUNTS_FILE`, `BOTS_LOG_DIR`.

## Tests

```bash
.venv/bin/pytest -q
.venv/bin/ruff check .
```
