# Treasury: the exchange's own two accounts

`fees@exchange` and `treasury@exchange` are ordinary `user_users` rows, created by
`V8__system_accounts.sql`, so the balance register and the ledger work on them unchanged. They
are `UserType.SYSTEM`, which is what keeps them out of every customer path: `User.canLogIn()`
requires `PRIMARY`, and `EligibilityService` refuses a `SYSTEM` row before it looks at anything
else. **The null password is belt and braces, not the rule** — a house row is `ACTIVE`, and some
later admin screen could give it a verified email and a second factor without meaning to.

| Account | Holds |
|---|---|
| `fees@exchange` | what trading earned: every fee in, every rebate out, and nothing else |
| `treasury@exchange` | what the house works with: what has been swept, what an admin funded |

**Nothing is ever paid out of `fees@exchange`.** It is moved to treasury and spent from there,
so its ledger reads as fee sweeps in and transfers out, and nothing else.

**Staff move money two ways, and no third.** On the admin Treasury page an `OPERATOR` moves fees
on to the treasury, and pays a customer out of the treasury, each behind a fresh code
([admin.md](admin.md)). `HouseTransferService` makes each one a debit and the same credit,
ledgered `TRANSFER` under `ReferenceType.ADMIN` with the move's id, so a retried request moves
nothing twice; the audit row, written in the same transaction, is the record and holds the
reason. A payout goes to a customer's main account only: never a sub-account (the customer moves
it there), never a copy account (it would distort the follow's profit share), never the house.
**There is no way to credit anyone from nothing**: every unit a customer is given came out of
the treasury, so the books always say whose money it was.

**The sweep counts what it swept, never what the account holds.** `FeeSweepService` credits
earned minus the sum of the fee account's own `FEE_SWEEP` entries. Read from the balance, every
move to the treasury would look like fees not yet swept, and the next run would sweep them
again.

## Revenue is a bag of coins

A buyer pays their fee in base, a seller in quote, so the fee account fills up with whatever
was traded. **Everything is recorded and shown in native units.** Admin never values it: a
converted total answers "what is it worth now", which is a different question from "what did we
earn", and holding both in one number is how a page starts lying.

## The credit is swept, not settled

Settlement does **not** credit the fee account. The trader's fee debit is the real movement and
is already written in-transaction with its trade id; the house side is derived from it.
`FeeSweepService` reconciles it instead: per asset, what customers have paid in fees less what
they have been paid in rebates, minus what the account already holds, credited as the
difference under `LedgerEntryType.FEE_SWEEP`.

**It reconciles to a total rather than consuming a window, so it has no watermark and no resume
point.** The position is the balance itself. A run that died before committing wrote nothing and
the next one computes the same difference; a run that died after committing is already counted,
so the next one finds nothing to do; an outage of any length is one larger difference. Two runs
overlapping serialise on the account's own balance row, which is locked with
`findForUpdate` **before** either sum is read -- without that lock this design is not safe.

A window would have to answer "where did I get to", and every answer has an edge: a settlement
transaction that began before the cut and commits after it writes a row the sweep has already
passed, lost permanently and invisibly. A difference has no edge to get wrong.

**A negative difference waits.** Rebates can outrun fees in a window. Nothing is paid out of
this account, so the next run's larger `earned` absorbs it rather than debiting now and leaving
a movement no customer row explains.

This is about blast radius, not speed. Aggregated per batch the synchronous version would cost
one row update and one ledger insert per asset per batch, which is noise next to the rows a
batch already writes. But settlement is the one place where a bug moves money wrongly and the
one place that bounds throughput ([scalability.md](scalability.md)), and none of this needs to
happen there.

**It runs in `spot-settlement`**, which is the only thing that writes balances. A scheduled job
elsewhere would make that false.

**What it costs: the fee account lags by one sweep interval.** The books do not balance
continuously, and a sweeper that stopped on Tuesday is invisible unless something says so, so
**the treasury page says when fees were last swept** -- the newest `FEE_SWEEP` entry's
timestamp. Without that, sweeping is strictly worse than settling.

**Sweep the recorded value, never a recomputed one.** `Money.applyBps` truncates toward zero, so
a fee is rounded in the user's favour by up to 1e-8. Re-running the calculation on the house
side would reintroduce the asymmetry that today's code does not have.

## What keeps it cheap

`earned` would otherwise sum the ledger from the beginning of time on every run. Two fee rows
per trade is a table that only grows, so `wallet_fee_hourly_totals` (V9__fee_rollup.sql)
summarises customer-side fee flow one closed hour at a time: `earned` is those hours plus the
ledger from exactly where they end.

**The boundary is half-open at both ends.** An hour in both halves over-credits, and it is the
one error here that repeats rather than corrects itself, so it is tested against a real
database rather than reasoned about.

**Hours are rolled in order and never skipped**, because the watermark is the newest stored hour
and a gap would read as "already done". An hour with no fees writes nothing and is simply
re-examined, which costs nothing. **An hour is rolled only once safely closed** -- a transaction
that began before it ended can commit after, so `FeeRollupService.MARGIN` is how long a
settlement transaction is allowed to take. **The first ever run starts at the oldest fee row**,
not at now, or everything before it would be in neither half forever.

This is a summary, not a pointer: a wrong rollup row can be recomputed from the ledger and
compared, and once repaired the next sweep corrects itself. A wrong pointer moves money and
cannot be recovered from. That difference is the whole reason for the shape.

## Reconciliation

Two assertions make the whole scheme trustworthy, and both run nightly in the same job
(`FeeSweepService.verify`):

- **the summaries agree with the ledger they summarise.** A mismatch means a rolled-up hour is
  wrong -- a botched backfill, or a row that landed in an hour already closed. Re-roll the
  affected hours and the next sweep corrects itself, because the sweep reconciles to a total
  rather than adding what it found since last time.
- **the account never holds more than customers paid.** Lagging behind is the ordinary state
  between sweeps; being ahead is money no customer row explains, which means something wrote to
  the account outside the sweep.

**The check reads the ledger from the beginning, never the summaries.** A check that trusted
what it is checking could not tell you the summaries are wrong. That full sum is the expensive
query here, which is why it runs nightly and the sweep does not.

**Customer holdings exclude `SYSTEM` rows.** The sum of `wallet_balances` is what the books say
customers hold — a liability, not reserves, since nothing here touches a blockchain
([white-label.md](white-label.md)). Leave the house rows in and our money is counted as theirs.
