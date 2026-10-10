# Style

What the code looks like is in the code: match the file next to the one you are editing. This
file keeps only the rules whose reason is not visible there, so that "check the existing style"
does not copy an exception as if it were the pattern.

## Everywhere

- **Reuse before you write it again.** Used in one place: keep it there. Used in two: move it
  somewhere shared. Duplicate only when the shared place would be wrong, and say why in a
  comment.
- **Comments are one or two lines, and only for what is not obvious.** Say why, not what. If it
  takes a paragraph it is a design decision: write it once on the class. Never repeat a rule
  from AGENTS.md or `docs/reference/`; point to it.
- **Name files after what they hold**, never `helpers`, `utils` or `misc`.
- **Tests prove what they claim.** Cover the failure cases. If a test exists to catch something,
  break that thing and check the test fails. `@DisplayName` states the rule being proved, so a
  failure reads as a sentence. A pure-logic class gets a plain JUnit test beside it, no Spring;
  `*IT` tests need `docker compose up -d` and abort without it.

## Backend

- `apps/` deploys, `libs/` is shared. Gradle paths follow the tree. Use `implementation`; `api`
  only when a consumer needs the type on its own compile classpath.
- **Does more than one app need it?** One app: the service lives in that app. Several: it goes
  in `libs/domain/*`, throws plain exceptions and owns no error code
  ([reference/error-codes.md](reference/error-codes.md)). Dependencies go one way:
  `user ← wallet ← spot`, and a domain module never depends on an app.
- **One package per table**, holding the entity, repository and enums
  ([reference/entities.md](reference/entities.md)). Controllers and services stay at the
  feature root; never `controller/` and `service/` layers, which spread one feature across
  folders. A WebSocket is its own feature package, organised by the connection, not by the
  domain it carries. No exceptions: `fee.tier` holds the published levels and `fee.usertier`
  which level each customer is on, though one is read through the other.
- **What two features need becomes its own feature**, not a folder inside one of them. The URL
  does not have to move with the package: packages group by who owns the data, paths by what
  the client is looking at.
- Every controller returns a DTO, never an entity. `HttpApiResponseBodyAdvice` wraps the
  return; it applies only to `com.exchange`, so Actuator and springdoc keep their shapes.
- A crypto primitive lives in `libs/common`, framework-free, key material by constructor
  ([reference/credentials.md](reference/credentials.md)).

## Frontend

- **`app/` is routing and nothing else.** A `page.tsx` awaits its params, composes feature
  components and exports `metadata`. Every page lives under `[lang]/`.
- **One flat folder per feature** with its components, hooks and requests. No top-level
  `components/`, `hooks/` or `services/`. What two features need moves to `lib/` or
  `components/ui/`; a helper imported from a third feature's folder is the signal to move it.
  `app/**/_components/` was considered and rejected: the order form, balances and the ticker
  each outlive the route that first needed them.
- **A feature nests only where its routes do.** `features/my/` is the one that does, because
  `/my` is an area of five sections rather than a screen: `dashboard/`, `assets/`, `orders/`,
  `account/`, `subaccounts/`, and `shared/` for what the area's own sections have in common --
  its BFF client, the rail and the filter controls. One section reaching into another's folder
  says the thing belongs in `shared/`. Everywhere else, flat.
- **Surfaces, three levels, not a matter of taste.** In the page flow: `bg`, no fill, hairline
  `border-border` between rows. Floating over the page (menu, modal, toast): `surface` with a
  border and shadow. Interactive (inputs, the hovered row, the active item): `surface-raised`.
  That flatness is what makes a trading screen look dense.
- **One type scale on the account pages**, so a page does not read as three different UIs.
  The sidebar names the page, so a page whose name is already in it carries its `h1` as
  `sr-only` rather than repeating it on screen.

  | | |
  |---|---|
  | page title, where one is shown | `text-xl font-semibold` |
  | section heading | `text-base font-semibold` |
  | body: rows, lists, table cells, tabs, inputs, buttons | `text-base` |
  | captions: table headers, field labels, hints, notes | `text-sm` |
  | badges and pills | `text-xs` |

  The trading screen is the exception and passes `compact`, which keeps its panels dense:
  `text-xs` bodies and tighter rows. A component used in both places branches on that prop
  rather than picking one size for both.
- **No literal user-facing string in a component**: `useT()("login.title")`, typed keys, and
  `messages.test.ts` fails when `ko.json` and `en.json` disagree.
- Money in the browser is a decimal string ([reference/money.md](reference/money.md)); the
  session, `lib/bff` and Next 16 rules are in
  [reference/frontend-session.md](reference/frontend-session.md).
- Vitest and Testing Library for logic and components; Playwright only for the flows where a
  regression is expensive. `npm run build` type-checks.
