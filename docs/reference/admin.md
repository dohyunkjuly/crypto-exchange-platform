# Admin

Staff operate the exchange through `apps/admin-bff` (:8083) and the `admin/` Next app (:3001).
Neither shares a process, a host, a cookie or a login with what customers use. admin-bff is a
thin edge: one controller and one service per feature, the rules in the domain libs
(`FeeTierService`, `MarketListingService`, `KycService`) that web-bff could call but does not.

## What staff can do

Each is a page in `admin/` and a package in admin-bff. Every write is an `OPERATOR`'s, audited;
the ones marked *code* also need a fresh authenticator code.

- **Staff themselves** (`admins`). A `SUPERADMIN` creates an admin (*code*), disables one,
  changes a role (*code*) and resets a locked-out admin's TOTP (*code*). There is no recovery
  code for staff: this, or the CLI below, is how a lock-out ends.
- **Dashboard.** Today's sign-ups, trades, volume and fees against yesterday, per market too, and
  when fees were last swept. A figure with no data is left out rather than shown as zero.
- **Users.** Find a customer by email or id. Their file: status, master or subs, balances, API
  keys, sessions, and tabs for their orders, trades, ledger and fees & limits. Actions: freeze
  (`SUSPENDED`: no login, no trading, sessions revoked), suspend trading only (login, reading
  and cancelling still work), revoke every session, revoke one key. Freezing a master freezes
  its subs and unfreezing releases only those it took down; suspending trading is read for a
  sub through its master, so no sub row is written (`UsersService`).
- **Fees & limits, per customer** (`overrides`). A fee override (*code*), a cap on open orders
  across every market, and a cap in one market -- each with a reason, who granted it and an
  optional expiry. A fee override is set on the master only, because a sub pays exactly what its
  master pays ([sub-accounts.md](sub-accounts.md)); order limits stay per account, since they
  stop one account flooding the book. All apply from the next order.
- **KYC review.** Approve or reject a submission with a reason (*code*). A submission on the web waits
  for it, in development too: the auto-approver that once stood in is gone.
- **Copy leaders** (`copy`). A customer leads without staff approval. Suspend a leader and
  reinstate one, each with a *code*. Suspending takes a reason the customer never reads. Suspending closes every follower's copy account, and reinstating does not reopen them
  ([copy-trading.md](copy-trading.md)). Give a leader a profit share of their own, or put them
  back on the default (*code*): it applies to whoever follows from then on.
- **Copy settings** (`copy`). The leader's share of profit (for whoever follows from then on;
  a follower keeps the rate they started with), the failures that close a copy account, the
  limits and the minimums: one row, saved whole (*code*). A change applies from the next copy.
  The slippage cap on a copied buy is not here: each follower sets their own.
- **Markets.** List a pair (*code*), open it, move it between `TRADING` and `CANCEL_ONLY`
  (*code*), edit its limits (*code*). See the rules below.
- **Fees.** The spot schedule, one row per level, edited in place (*code*): the volume it
  asks for and its maker and taker rates. A new level goes on top. Which level a customer is on
  is never set here: a nightly job sets it from the family's volume ([fee-tiers.md](fee-tiers.md)).
- **Orders, trades, ledger.** Everyone's, newest first, with filters; and one customer's on
  their file. Read only.
- **Treasury.** What the two house accounts hold, in native units, and when fees were last
  swept. Move fees on to the treasury (*code*) and pay a customer's main account out of the
  treasury (*code*), each with a reason; the latest moves are listed there and every one is in
  the audit log ([treasury.md](treasury.md)).
- **Audit log.** Every write above, filtered by admin, action, target and time.

## Rules

- **Staff never touch a customer's order.** No cancel, no amend, no placing on their behalf. An
  order is the customer's instruction; a market in `CANCEL_ONLY` is how staff stop trading, and
  the customer's own cancel always works.
- **A customer is never told why.** Freezing and suspending trading each take a reason for the
  audit row; the customer sees only that they cannot trade (`canTrade: false`, a neutral
  refusal). No flag, wording or code on any customer surface names a sanction or its reason.
- **Every write is audited in the same transaction.** `admin_audit_log(admin_id, action,
  target_type, target_id, before, after, ip)`: no change without its row, no row without its
  change (`AdminAuditService.record`, `MANDATORY`). `before` and `after` are JSON of the fields
  that changed. `action` is a fixed vocabulary, one per operation, never renamed. Reads are not
  audited; that is the access log's job.
- **Anything that moves money or a price needs a fresh code.** The request carries
  `X-Admin-TOTP`, checked at the edge (`@RequiresStepUp`) before the service runs, and spent on
  use. A session alone is never enough for a write that cannot be undone.
- **A role is a floor, not a permission set.** `VIEWER` reads everything; `OPERATOR` does the
  day's work; `SUPERADMIN` also manages staff. Nothing is composed per admin.
- **Markets.** Fixed at listing, never edited: the pair, the engine partition
  ([command-partition.md](command-partition.md)), the price tick and the quantity step --
  resting orders and every client's rounding were made to them, and the entity refuses to write
  them. A market is listed in `PRE_TRADING` and opened with a second call, so a mistake never
  trades; the engine makes the book on the first order and the apps' market lists refresh within
  a minute. Staff set `TRADING` and `CANCEL_ONLY` only: `HALTED` would do what cancel-only does,
  and delisting is not a staff action. A limit off the market's own step is refused, since no
  order could meet it. admin-bff has no Kafka, so the partition count is
  `ENGINE_PARTITION_COUNT`, with no default.
- **Fee schedule.** Level 0 starts at no volume, each level asks for more than the one below,
  the taker fee is never negative and a maker rebate never exceeds it -- otherwise a trade
  between two customers costs the exchange. No level is removed: a customer on it would have no
  rate. A change applies to new orders; an order keeps the rate stamped at acceptance.
- **Error codes are admin-bff's own**, in `common/errors/AdminErrorCode.java`, in the ranges of
  [error-codes.md](error-codes.md).
- **The edge is the customer edge, unchanged.** `admin/` talks to admin-bff only,
  `credentials: "include"`, never retries a mutation, renders and does not fetch
  ([frontend-session.md](frontend-session.md)). Pages are status plus one action; a blocker is
  an alert at the top, never an explainer.

## Staff accounts

**Separate app.** web-bff is on the public domain; admin-bff is deployed on an internal network,
IP-allowlisted, never public. That only holds when they are different processes: one `/admin/**`
route in web-bff is one misconfigured rule away from the internet, and the customer bundle never
ships a screen a customer must not see.

**Separate identity.** `admin_users` is its own table, never a role bit on `user_users`. A
customer row cannot become staff by one column changing, and nothing that reads customers (the
trading gate, KYC, eligibility) ever meets a staff row.

**TOTP is mandatory**, enrolled at first login before anything else is shown. Sessions,
passwords and TOTP seeds are stored as [credentials.md](credentials.md) says for customers, in
`admin_sessions` and on the `admin_users` row: opaque tokens in an `admin_session` cookie, 8
hours idle, revoked by row. The interceptor is fail-closed: every route under `/admin` is
authenticated unless excluded on purpose.

**The first admin comes from the CLI**, never a migration: a password in a migration file is a
password in git forever.

```
docker compose --profile full run --rm admin-bff create-admin --email ops@example.com --role SUPERADMIN
```

`create-admin` is built into the app's jar and runs wherever the app runs. It prompts for the
password, hidden as typed, creates the row and prints nothing else. The admin enrols TOTP at
first login.

## Local stack

`docker compose --profile full` runs admin-bff and admin. Nothing seeds an admin, on any
profile: the first one is created with the CLI above, so the only way to become staff is the
same everywhere. `bots` does not touch admin-bff.

## Not now

Withdrawal approval. A balance adjustment that credits or debits a customer from nothing is not
coming: money given to a customer is paid out of the treasury ([treasury.md](treasury.md)).
