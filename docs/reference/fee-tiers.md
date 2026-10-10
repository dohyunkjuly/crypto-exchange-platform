# Fee tiers: how a customer's level is set

The published schedule is `fee_tiers`, one row per level, edited by staff
([admin.md](admin.md)). Which level a customer is on is `fee_user_tiers`, written once a night
by `FeeTierJobs` in spot-settlement (the only app that assigns one) and read on every order by `FeeQueryService`. Nothing on the
order path computes volume; it reads the row the night left behind, or level 0 if there is none.

## The rule

A customer's level is the highest rung whose `min_30d_volume` their **family's trailing 30-day
volume** reaches. The family is a master and every one of its sub-accounts. The row is written
on the master only, because a sub pays exactly its master's rate
([sub-accounts.md](sub-accounts.md)) -- so what a sub trades earns the rate it pays.

- **Days are UTC days**, and the window is the 30 that closed before the run: yesterday and
  the 29 before it. It slides daily; it is not a calendar month.
- **Volume is measured in one asset**, `exchange.fee.volume-asset` (USDT). A market quoted in
  it counts at face value. A market quoted in anything else is converted at **that day's
  close**: the price of the last trade of the day on the market that prices its quote in the
  volume asset (ETHBTC converts at BTCUSDT's close). The day's total is converted once, not each
  trade at its own price, so a customer can check the figure against a chart.
- **A quote that cannot be priced does not count.** If there is no market pricing it in the
  volume asset, that market's volume goes to nobody's tier. It is logged nightly for as long as
  it trades, never fatal: one odd listing must not stop everyone else's tier.
- **A new level applies to new orders.** An order keeps the rates stamped on it at acceptance,
  so a promotion at 00:20 changes nothing already on the book.
- **A staff override wins.** `FeeQueryService` reads the master's override first and only then
  the tier; the job never touches overrides.

## The nightly run

`exchange.fee.tier-cron` (00:20 UTC) does two things, in order:

1. **Recount** (`VolumeRecountService`). For each of the last three closed days, the day's
   `spot_trades` are summed per account and per market -- both sides of every trade, buyer and
   seller -- converted, and written to `fee_user_daily_volumes`, one row per account per day.
   The day is deleted and rewritten in one transaction. Three days rather than one so a trade
   that settled after midnight still lands in its own day, and two missed nights are caught up
   without anyone noticing.
2. **Place** (`TierRefreshService`, `UserFeeTierAssignmentService`). Every account with volume
   in the window resolves to its master, and every master that already has a tier row is
   visited too -- that second set is what demotes a VIP who stopped trading, since nothing else
   would ever look at them again. Each master's family is summed over the window and its row
   written, whether or not the level moved. `computed_at` always advances, so a stalled job
   shows up as old rows (`UserFeeTierRepository.findByComputedAtBefore`).

The volume table is never written on the fill path. It used to be planned as an upsert per
fill plus a repair pass; the repair pass alone is the whole mechanism now, because it is exact,
it is rerunnable, and it costs the settlement pipeline nothing. The comment in `V3__fee.sql`
describing the upsert predates this and stays, since a migration that has run is never edited.

## Why it can rerun and why it runs here

Every write is absolute: a day's rows are what the trades say, a tier row is what the window
says. Running twice, or two instances at once, reaches the same rows. A run that dies halfway
left some days rewritten and some not; the next night rewrites them all. Nothing needs to have
run for trading to be correct -- a missing row is level 0, the published base rate.

It runs in spot-settlement for the same reason the treasury jobs do
([treasury.md](treasury.md)): it is derived from what settlement wrote, and it is off the
settlement path -- no event waits on it. The scheduler has two threads
(`spring.task.scheduling.pool.size`) so a long recount never holds up the fee sweep; settling
fills is on Kafka's threads, not these.

## Not done

Showing a customer their 30-day volume and the distance to the next level; a "recompute now"
for staff; the `min_asset_id` / `min_asset_balance` columns on `fee_tiers` (a hold-to-qualify
rule) are unused.
