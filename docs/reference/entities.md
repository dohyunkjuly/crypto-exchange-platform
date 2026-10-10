# JPA entities

- **Never `@Data` on an entity.** Its `equals` compares every field, so an entity with no id
  yet that is put in a `HashSet` can no longer be found the moment it is saved. Entities use
  `@Getter` and `@Setter` plus a hand-written `equals` and `hashCode` on the id only.
- **Rows that two writers race on carry `@Version`.** `Balance` and `SpotOrder` do: two
  concurrent fills on one order would otherwise be a lost update. Where a hot path must not fail
  on an ordinary race, it uses a conditional `UPDATE` (`SpotOrderRepository.applyFillAtomically`,
  `claimAmend`) instead of load-then-save.
- **A customer id is random, not counted** (`@RandomUid` on `User.id`). A counter tells anyone
  who signs up twice how many did in between. It is still a `BIGINT`, ten digits, so every table,
  message and the engine that carries a user id is unchanged; a taken number is drawn again, and
  the primary key is the final guard. The column has no `AUTO_INCREMENT`, so a SQL insert must
  give an id. Never sort users by id to mean "newest": use `created_at`.
- **An API key's id is a ULID** (`@UlidId`, V15), like an order's: the web carries it in key lists
  and in the URLs that edit and revoke a key, so it must not count either.
- Column names derive from field names (`passwordHash` → `password_hash`). Write
  `@Column(name = ...)` only when the two differ.
- One package per table: entity, repository and its enums together, because the enums are the
  names of that table's column values. `dto/` and `exception/` subfolders appear when a package
  gets crowded, not before.
