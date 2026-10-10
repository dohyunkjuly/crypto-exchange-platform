# The trading gate

Trading needs a verified email, KYC `BASIC` and an active second factor. Submitting KYC needs the
verified email. `EligibilityService.requireCanTrade` in `libs/domain/user` is the one
implementation.

**The gate sits at each app's edge, not in `OrderService`.** Both apps already load the user to
authenticate, and checking in the service would cost a read per order.

- web-bff: `@RequiresTrading` on placing and amending.
- spot-open-api: the HMAC filter calls `requireCanTrade` for placing and amending. **This
  cannot be left out.** An API key stays valid on its own, so without the call a bot keeps
  trading on lapsed KYC forever.

**A sub-account is gated by its master.** It has no email, KYC or second factor of its own, so
`requireCanTrade` follows `parent_user_id` for a `SUB` row: the one read this gate ever costs.
A `SUSPENDED` account is refused before anything else -- the sub's own row **and** its master's,
since freezing a master must stop its subs' keys too, whatever their rows say.
[sub-accounts.md](sub-accounts.md)

**An admin can suspend trading and nothing else** (`trading_suspended_at` on `user_users`,
V13). The gate refuses it after the frozen check, on the row or its master's, so a master's
suspension stops its subs with no sub row written. It also refuses what would carry trading on
around it: minting or widening a trading key (a read-only key is still allowed) and any
sub-account transfer in the family. Orders already on the book stay; staff never cancel them.
Codes: web-bff 2031, spot-open-api 2115.

**A customer is never told they were suspended, or why.** The refusal says only that trading
is not available, `GET /v1/account` shows `canTrade: false` and nothing more, and the web shows
the error on submit. The reason is for staff, on the admin customer file.

**Never gate cancelling or reading.** A lapsed account must still be able to get out of its
positions. Amending is gated because it can add exposure; cancelling never can.

**A market with no engine is paused, and that gate is in `OrderService`.** The engine announces
each partition it is running, once a second, on `exchange.spot.engine-status.v1` -- only once
its replay has reached the committed offset and only while its consumer is polling
(`EngineHeartbeatPublisher`). Every app with an order path reads the topic (`EnginePresence`,
one consumer group per instance, judged by the receiver's clock) and `placeOrder` and
`amendOrder` refuse a market whose partition has been silent for five seconds: web-bff and
spot-open-api code 5031, a 503. Before this, an order placed while the engine was down was
accepted with a 202 and sat in Kafka with its funds locked until the engine returned, to be
matched at whatever the price was then. A fresh app instance has heard nothing and pauses
every market for its first seconds; a broker it cannot reach pauses them all, which is right,
since no order could reach the engine either. `paused` on the markets listing says so, and the
web shows it in place of the order form. A copy that meets it is skipped, not failed. Whether an
engine is up is the operator's monitoring (`/actuator/health`, the topic itself), not a staff
screen. **Cancelling is still never refused**: a cancel
waiting in Kafka is exactly what the trader wants.

`GET /v1/account` answers "why can't I trade" as booleans, so a bot learns it without a failed
order. Codes: 2107 KYC, 2108 MFA, 2109 email.
