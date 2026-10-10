# Spot copy trading

A leader trades one account; a follower funds a copy account tied to that leader, and each of
the leader's orders that fills is repeated in the follower's account, scaled to the follower's
money. A share of the follower's realized profit goes to the leader. The domain is
`libs/domain/copy` (last in the chain `user <- wallet <- spot <- copy`), the copier is
`apps/spot-copy`, the customer's pages are `/copy` and `/my/copy` on web-bff and `web/`, and
staff's are `/copy/leaders` and `/copy/settings` on admin-bff and `admin/`.

## The two accounts are sub-accounts

Nothing new in the wallet, the gate or settlement: a sub-account is already separate balances
and orders under one identity, gated by its master, funded by an idempotent transfer
([sub-accounts.md](sub-accounts.md)). `user_users.sub_purpose` adds two kinds of `SUB`:

- **`LEAD`** -- the leader's portfolio, made once when the customer first leads. Only its
  orders are copied; the leader's own account stays private. It trades markets quoted in
  `exchange.copy.quote-asset` (USDT) only, so every copied buy spends USDT and every realized
  profit is USDT by construction.
- **`COPY`** -- one follower's copy of one leader, funded in USDT from the master. **The
  follower never places an order on it**; only the copier does, and an order from anywhere
  else is refused.

Neither counts towards the master's twenty ordinary subs, and `SubAccountService.list` and
`get` never return them, so nothing a master does to an ordinary sub by hand -- freeze it, mint
it a key, move its funds -- can reach one. The follower's KYC, freeze and trading suspension
apply to a `COPY` account through its master, as to any sub: a follower who cannot trade stops
being copied.

## The order path

`OrderService` calls `SubAccountOrderHook` for a sub-account's order, in the placing transaction
and before the funds are locked; a master's order never gets there. `CopyAccountOrderHook` is
the hook:

- a `COPY` account placing by hand is refused (`5029`);
- a `LEAD` account on a market not quoted in the copy asset is refused (`5030`);
- an **active** leader's order is written to `copy_lead_orders` with the **fraction** it was of
  the account: the cost over the available USDT for a buy, the quantity over everything held
  of the base for a sell. It is recorded now because by the time the order fills the balance
  has moved and it could not be worked out afterwards. A suspended or closed leader's orders
  are their own and copy to nobody.

**An app that places orders must scan `com.exchange.copy`**, or the hook is absent and nothing
fails: web-bff and spot-open-api each hold that with a test (`CopyOrderPathIT`,
`CopyHookWiringIT`).

## What is copied, and when

`spot-copy` consumes `exchange.spot.events.v1` -- kept forever, delivered at least once
([delivery-guarantees.md](delivery-guarantees.md)) -- in its own consumer group. **The matching
engine and the event messages are unchanged**: `TradeExecuted` and `OrderCanceled` already say
when an order closed and how much filled. A batch that fails is handed back with `nack`, which
rewinds the consumer; returning without acknowledging would not.

**One copy per lead order, when it closes with something filled.** `LeadOrderService` adds up
the fills on the `copy_lead_orders` row, counted once by sequence. **An order has filled when
what filled covers what was ordered, never because a trade says so**: the engine stamps every
trade of a taker with the order's final status, so the first of five trades says `FILLED` as
much as the last (`CopyLeadOrder.isFilled`). A resting order cancelled after a partial fill
copies the filled part; an amend the engine took moves the fraction with it.

`CopyService` then gives each active follower its copy through `OrderService.placeCopyOrder`,
the copier's door, which skips the hook and writes the `copy_orders` row -- unique on
`(lead order, follow)` -- in the placing transaction, so a redelivered event finds the row and
does nothing. The copy is an ordinary order: fees at the follower's tier, limits, the gate and
locked funds all apply, and settlement stays the only writer of orders and balances.

**In a new random order for every lead order, eight at a time.** Each copy buy moves the price
for the next, so whoever is placed last finds the least inside the cap. Shuffling spreads that
over every follower instead of the same ones each time, and placing eight at once shortens the
time the price has to move; eight stays under the database pool so the listener keeps a
connection. A copy that breaks for a reason other than a refusal is rethrown once the others have
finished, and the batch is read again: the copies already placed have their rows and are skipped.

- **A buy is `LIMIT IOC`** at the leader's average fill price plus the follower's slippage cap,
  on the tick: it fills up to the cap and the rest is cancelled, so a follower never pays more
  above the leader than they chose. **The cap is the follower's own**, set when they follow
  (`copy_follows.slippage_cap_bps`, 0.1% to 5%, the form opening at 0.5%): how much to pay to
  stay in step is the follower's decision, not staff's. A cap set tight misses more copies, and
  those count as failures like any other.
- **A sell is a market order**, uncapped: a follower must not be left holding what the leader
  has sold because the price moved.

### Sizing (`CopySizing`)

The follower picks a mode when following:

- **Fixed ratio**: a buy spends the same fraction of the copy account's available USDT that the
  leader's buy did. The leader had 1,000 free and spent 500; a follower with 500 free spends 250.
- **Fixed amount**: every buy costs the amount the follower set, or what is left if less.

A sell, in both modes, sells the fraction of the holding the leader sold. Quantities go onto
the market's step; below the market's minimum is a failed copy.

### Failures

A copy fails when the account has too little balance, a buy cannot fill inside the cap, the
order is below the minimum, the market is not accepting orders, or the gate refuses the
follower. **It is not retried and there is no manual sell.** A copy's own trade makes it
`FILLED` and clears the account's count of failures in a row; a cancel or refusal makes it
`FAILED` and adds one. **At `failure_limit` (5) the account is closed**: five in a row means the
follower is no longer getting what they followed for. A sell with nothing to sell is `SKIPPED`,
neither a failure nor a success.

**Known gap: the copier reads balances settlement writes.** A leader who buys and sells again
before settlement has applied the follower's buy is copied against a balance that has not
caught up; the sell is skipped and the follower keeps the position until the leader's next sell
of it or the account closes.

## Closing a copy account (`CopyCloseService`)

By the follower stopping, by the failure limit, or by the leader no longer leading. Two steps,
because the second cannot be done from the first:

1. `begin` moves the row to `CLOSING` -- whoever moves it does the selling; a second caller does
   nothing -- and places a market sell for every holding worth more than
   `close_sell_threshold` at the market's last trade, on the market's step. USDT and dust are
   left alone; a market that refuses the sale leaves the holding to be returned as it is.
2. `finishIfSettled`, asked by `CloseSweeper` every `exchange.copy.close-sweep-ms`, returns
   everything to the follower's main account and marks the row `CLOSED` only once the account
   has **no order open and nothing locked**: the proceeds of its last sale arrive when settlement
   has applied the fill, on settlement's clock. The profit share is settled in the same
   transaction, before the funds go back.

`CloseSweeper` also begins closing every active follow of a leader who is suspended or closed,
with the reason that says which. Staff's decision and the leader's only change a status;
admin-bff and web-bff place no order.

## Leaders (`CopyLeaderService`)

`ACTIVE` <-> `SUSPENDED` by staff; `ACTIVE` -> `CLOSED` by the leader. There is no staff
approval: `V27` removed it, and `APPLIED` and `REJECTED` stay in the enum only because a stored
value is never removed. A leader who leads again after stopping gets the same row and lead
account back. A display name is one leader's.

- **Becoming a leader is one step** (`CopyLeaderApplicationService`, `V23`): name, description,
  whether the positions are shown, and the amount moved from the customer's own account into
  the lead account, between `lead_min_balance` and `lead_max_balance`. The application, the
  `LEAD` account and its funding exist together or not at all, so a leader is never listed
  empty. The bar is the API key's (`EligibilityService.requireCanCreateApiKey`: verified to the
  level that may trade unattended, with a second factor). The account leads at once; staff can
  suspend and reinstate it ([admin.md](admin.md)), each with a fresh code and audited.
- **A customer is never told why**: the leader's page says `ACTIVE`, `UNAVAILABLE` or
  `CLOSED`, and a follower of a suspended leader reads the same
  `LEADER_CLOSED` as one whose leader stopped.
- **The lead account keeps `lead_min_balance` (500 USDT) while leading**
  (`CopyLeadAccountService`): only USDT above it can be taken out. Trading under it is not
  stopped; the minimum is on taking money out. Once not leading, anything comes out.
- **A leader stops only once the lead account is flat**: no order open, nothing held but USDT
  and dust (`CopyCloseService.requireFlat`, `8016`). Each sale on the way is copied, so the
  followers are sold down at the leader's own pace rather than by a burst of market sells when
  the leader goes. Staff's suspension is unconditional and does sell them down.
- **The profile's figures are always public; the positions are the leader's to show.**
  `copy_leaders.show_portfolio` (`V22`, on by default, the leader's switch on `/my/copy/lead`)
  decides whether the profile carries the lead account's holdings, valued at each market's last
  trade, and its last fifty fills. The leading balance shown is the last nightly snapshot's.
- **Trading the lead account.** A lead account never logs in. By hand, its owner names it from
  their own session: `X-Trading-Account: lead` on `/bff/orders`, `/bff/trades` and
  `/bff/wallet/balances` (`@TradingAccount`, on those three controllers only) and
  `/bff/ws/user?account=lead` for its private socket. **The name is a fixed word, never an
  id**: the account is found from the session (`TradingAccounts`), so nothing a client sends
  can point at someone else's account. By key, `/bff/copy/lead/api-keys` mints one with the
  owner's authenticator code, never withdrawing; on spot-open-api it is a sub-account's key like
  any other. The trade screen has an account switch, kept per tab (`tradingAccount.ts`); each
  account's lists are their own cache entries and one socket serves one account at a time.

### A leader's figures (`LeaderStatsJobs`, 00:30 UTC)

- **A snapshot a night** (`copy_leader_snapshots`): the lead account valued in USDT, each
  holding at the last trade of its market, and what was moved in minus out since the snapshot
  before. Written once; a night run twice is one night.
- **A return is the chain of snapshots with the money moved taken off each step**
  (`ReturnMath`, in the lib because web-bff draws the same chain). Funding is not a gain; a
  leader who doubles 100 and then adds 10,000 still shows the double. A missed night is a
  longer step, not a gap.
- **This is the one place a balance is valued at a market price** (`Valuation`). A return
  cannot be stated without one; nothing is paid or moved on what it says.
- `copy_leader_stats` is one row a leader, replaced whole: over 7, 30 and 90 days the return,
  the earnings and the deepest fall from a high; the copy success rate (of the last 30 days'
  copies with an outcome, how many filled); the funds copying. **Nothing measured yet is null
  and shown as a dash, never zero.** The follower count stays live.
- `/copy` is a grid of cards sorted by the window and figure the reader picks, with the
  month's line, a name search, favorites kept in the browser, and the product's one FAQ -- by
  decision, since it is the page read before putting money behind someone else's trades. The
  FAQ's numbers are the terms staff set, never typed into the text. Copy trading is its own
  section: `/copy` (leaders), `/copy/mine` (what the customer follows), `/copy/lead` (the
  customer as a leader); none of it is under `/my`.

## The profit share (`CopyProfitShareService`)

- **On realized profit only, net of fees, in USDT only.** Nothing is valued at a market price
  and nothing is ever taken in a coin. Profit is realized by a sell, which pays out in USDT, so
  the money the share comes from is already there.
- **Average cost per asset** (`PositionMath`, checkpointed in `copy_positions` with the market
  sequence counted up to): a buy adds what was received -- a fee taken in the coin is fewer
  coins -- at what was paid; a sell takes the sold share of the cost out and realizes the
  proceeds, net of a fee in USDT, over it. Read from `spot_trades` at settlement; nothing on the
  fill path writes it.
- **Cumulative, so a leader is paid once for each unit of profit**:
  `due = max(realized x rate - already paid, 0)`. A loss after a payout is not clawed back; it
  has to be made up before anything more is due.
- **The rate is the follow's own, frozen when the follower starts**
  (`copy_follows.profit_share_bps`): a rate that moved would bill settled profit again. Staff's
  setting is the rate for whoever follows from then on. **A leader can have a rate of their own**
  (`copy_leaders.profit_share_bps`, V26), set by staff on the leader and never by the leader;
  null means the exchange's. It is read once, when someone follows, and frozen like any other. The exchange takes none of it; its
  revenue is the trading fees the copies pay ([fee-tiers.md](fee-tiers.md)).
- **Settled** weekly by `ProfitShareJobs` (Monday 00:05 UTC, `exchange.copy.share-cron`) for
  every account still copying, on closing after the last sale has settled and before the funds
  go back, and on a withdrawal before the transfer -- the last two in the same transaction as
  the move. A weekly `copy_profit_shares` row is unique for its week, so a late or repeated run
  settles a week once; a missed week is not lost, since the formula is over the account's life
  and the next settlement finds it.
- **A short account pays what it has.** If a later copied buy spent the proceeds, what USDT is
  there is paid and the rest stays owed, found again by the same formula next time. Nothing is
  sold to pay a share: that would be a trade the follower did not choose.
- Paid from the copy account to the leader's **own** account as `PROFIT_SHARE_OUT` /
  `PROFIT_SHARE_IN`, their own ledger types so neither reads as a trade, a fee or a transfer
  the customer asked for.

## Settings (`copy_settings`, one row)

All staff's ([admin.md](admin.md)), saved whole behind a fresh code: the share rate (for new
followers), the failure limit, followers per leader
(300), copy accounts per customer (10, one per leader), the minimum to follow in each mode (100 / 10 USDT), the lead minimum (500) and maximum
(1,000,000), the close threshold (10). **A change applies from the
next copy**: the copier and web-bff read the row each time, and a copy already placed keeps its
price. Error codes are each app's own, in the 8000s ([error-codes.md](error-codes.md)).

## Tables

| Table | Holds |
|---|---|
| `copy_settings` | the one row of what staff set |
| `copy_leaders` | one leader: master, lead account, public name, status, who suspended them and why, its own profit share if staff set one |
| `copy_follows` | one copy account: follower, leader, mode, amount per order, slippage cap, the frozen rate, status, failures in a row, realized and shared profit |
| `copy_lead_orders` | a lead account's order, the fraction it was, and its fills so far |
| `copy_orders` | one copy of one lead order for one follow, or a closing sale; unique on `(lead order, follow)` |
| `copy_positions` | a copy account's quantity and cost per asset, and the sequence counted up to |
| `copy_leader_snapshots` | a lead account's value at the end of a UTC day, and what was moved in since the one before |
| `copy_leader_stats` | one row a leader: returns, earnings, drawdown, success rate, funds copying |
| `copy_profit_shares` | one settlement of one copy account: what was due and what was paid |

No foreign keys into user, spot or wallet ([migrations.md](migrations.md)).

## Testing it

`CopierIT` (both modes, a redelivered event copied once, a sell as a share of the holding,
five failures closing the account) and the amend tests need settlement stopped: `docker compose
stop spot-settlement spot-matching-engine spot-copy`, run with `cleanTest`, start them again.
`ProfitShareIT`, `LeaderStatsIT`, `LeadTradingIT`, `CopyFollowIT`, `CopyLeadersIT` and
`CopySettingsIT` run beside the stack and leave nothing behind. There is no bot for a leader or
a follower; the flow is driven by hand.

## Not now

A total stop loss on a copy account; a share of the follower's trading fees to the leader;
private lead portfolios; markets not quoted in USDT; the public API (copy accounts are web
only); a catch-up of the weekly share at start-up.
