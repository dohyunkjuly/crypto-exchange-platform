# Migrations, seeds and stored values

Flyway owns the schema. `libs/persistence` holds only the migrations and the JPA, Flyway and
MySQL dependencies, because the version numbers are one sequence across every domain.

`db/migration` starts with one baseline per domain, `V1__user` to `V4__spot`, written before
anything had been deployed. Each table's header carries the reasoning behind its shape; the
rules below apply to everything added after them.

**Reference data is a seed, not a migration.** `db/seed/` holds repeatable scripts
(`R__assets_and_markets.sql`, `R__fee_schedule.sql`): the markets and the fee schedule a
developer needs to try the system. Flyway re-runs a repeatable script whenever it changes,
so every statement in one is an upsert. `FLYWAY_LOCATIONS` in `backend/.env`, the compose
stack and the tests' `application.properties` add `classpath:db/seed`; a deployment lists its own markets and
publishes its own fees, and the apps refuse to start without a fee schedule at all
(`FeeQueryService.verifyBaseTier`). The one exception is a row that is part of the schema, such
as the singleton in `spot_exchange_order_limits`.

- **Never edit a migration that has run anywhere but your machine.** Flyway checksums it, and
  a changed checksum stops every later deploy. Add the next `V{n}__snake_case.sql` instead.
  Once deployed this cannot be undone.
- **Never rename or remove a stored enum value.** Enums are `VARCHAR` with
  `@Enumerated(EnumType.STRING)`, so the name is written into every existing row. Reordering
  the Java enum would silently change the meaning of every row too, which is why they are never
  `ORDINAL` or a MySQL `ENUM`. The same holds for a protobuf enum number once a topic or a
  snapshot has stored it.
- **Set defaults in Java, not in SQL.** Hibernate writes every column on insert, so a `DEFAULT`
  in a migration never fires for a new row. A `DEFAULT` is only ever for the rows that already
  exist when a column is added.
- **No foreign keys or JPA relationships across domains.** `user`, `wallet` and `spot` must be
  able to move to their own databases; a cross-domain key makes that impossible forever.
- Write the SQL by hand. On a table that holds balances, reading the `ALTER TABLE` before it
  runs is the whole point.
- Tables are `domain_prefix_plural`; constraints are `uq_`, `fk_`, `ck_` or `idx_`, then the
  table, then the columns. Money is `BIGINT` ([money.md](money.md)); timestamps `TIMESTAMP(6)`
  UTC, or `DATETIME(6)` past 2038.
