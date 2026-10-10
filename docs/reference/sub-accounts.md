# Sub-accounts

Separate balances, orders and keys under one identity, one level deep.
`SubAccountService` in `libs/domain/user`, `TransferService` in `libs/domain/wallet`; served
by web-bff (`/bff/sub-accounts/**`) and spot-open-api (`/v1/sub-accounts/**`, documented in
`api-docs/content/includes/_sub_accounts.md`).

## What a sub-account is

A `user_users` row of type `SUB` with `parent_user_id` set, and nothing else that makes an
account a person:

- **No password, no login.** Its email is virtual and random on both sides
  (`k3j9x2p1_virtual@wv7vw8elnq.com`): a column `user_users` requires and an
  identifier support can quote, never a mailbox. Nothing is ever sent to it, and nothing of the
  master's is in it -- deriving it from the master's address would publish whose it is. Everything
  about a sub is done by its master, from the web or with the master's API key.
  `User.canLogIn()` is false for a `SUB`, and every flow that turns an address into a session
  checks it: the password login, Google (a token could in theory carry the address), and
  password reset -- whose code the master's inbox would receive, which is how a sub would
  otherwise get a password after all.
- **No verification of its own.** Email, KYC and the second factor live on the master, and the
  trading gate resolves through it: `EligibilityService.gated(user)` follows `parent_user_id`
  for a `SUB`, the one case where the gate costs a read ([trading-gate.md](trading-gate.md)).
  A frozen account (`SUSPENDED`) is refused before any of that, for subs and customers alike.
- **No fee of its own.** A sub pays exactly what its master pays: the master's override, else
  the master's tier. The order path passes the master to `FeeQueryService`, and staff set a fee
  override on the master only ([admin.md](admin.md)). What a sub trades counts towards the
  master's tier ([fee-tiers.md](fee-tiers.md)).
- **Everything else is its own.** Balances, orders, trades, order limits and API keys are keyed
  by the sub's id like any user's. A request signed with a sub's key *is* the
  sub, and it sees only itself; the sub-account endpoints answer it `2111`.
- **One level.** A sub cannot have subs. At most `MAX_SUB_ACCOUNTS` (20) per master.
- **Never deleted.** Frozen instead: `SUSPENDED` stops placing and amending, and blocks
  transfers in either direction. Open orders stay and can be cancelled -- cancelling is never
  gated, as everywhere.

**A lead or a copy account is a sub of another kind** (`sub_purpose`,
[copy-trading.md](copy-trading.md)). It is gated and priced like any sub, but it is not one of
the twenty, and none of the endpoints here list it or act on it. A lead account is the one
sub its master trades by hand, from the master's own session.

`trade_group_id` is set to the master's id on every sub, for self-trade prevention across a
family one day. The engine ignores it today: STP is per user id.

## Keys

A sub's API keys are made by its master, **on the web only**, with the master's TOTP:
`ApiKeyService.createForSubAccount(masterId, subId, ...)` runs the gate and the step-up on the
master and mints the key on the sub. The same rule as the master's own keys
([api-signing-and-limits.md](api-signing-and-limits.md)): a credential that trades unattended
is not minted over the API. A sub's key is never withdrawal-capable.

## Transfers

Master to sub, sub to master, sub to sub under one master. Any asset, from the sender's
available balance, instant. `SubAccountService.requireTransferable` says whether the two sides
are one family and neither is frozen; `TransferService.transfer` moves the money: one
transaction, debit then credit, both ledgered `TRANSFER` with the transfer's id, so a failure
anywhere leaves both balances as they were.

`wallet_transfers` is the history and the idempotency. The id is the client's when it sends
one: a retry after a timeout with the same id returns the row that exists and moves nothing
again, the same shape as a replayed `clientOrderId`. No foreign keys into the user tables
([migrations.md](migrations.md)).

## Not now

Login for a sub through a real email; self-trade prevention across the family; the admin's
view of a family (that is the users page, [admin.md](admin.md)).
