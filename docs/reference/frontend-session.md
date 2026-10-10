# The browser, the session and web-bff

**Next renders. It does not fetch.** No `app/api/`, no server-side calls to web-bff, no data
fetching in Server Components, no route handlers. The session token is opaque, so Next's server
cannot check it; the cookie belongs to web-bff's origin, so `cookies()` only sees it in dev; and
a route handler would be a second BFF in front of the one we have.

**The browser talks to web-bff and nothing else.** Never spot-open-api. `lib/bff` is the one
place that knows web-bff exists: one base URL, one envelope unwrap, one error map, one socket.
Features call `api(...)`; nothing else calls `fetch` or names a port.

**`credentials: "include"` on every request.** The cookie is cross-origin. Without it every
authenticated call answers `NOT_AUTHENTICATED` and nothing tells you why.

**A cookie being present does not mean logged in.** The gate is a client component in
`(account)/layout.tsx` that redirects on `401`. The only `proxy.ts` is the locale redirect; it
makes no auth decision, because it cannot check an opaque token. `401` is never an error to
show: the session is gone and the gate handles it.

**Never retry a 4xx. Never retry a mutation.** `POST /bff/orders` and `PATCH /bff/orders/{id}`
answer `202` before matching, so a timeout tells you nothing. A retry is a second order, or a
second amend refused with 5026. React Query mutations are created with `retry: false`.

**Branch on error codes, never on message text** (`lib/bff/errors.ts`). Messages are copy
shown to users and are reworded without anyone searching the frontend; the UI translates a
code with `errors.<code>` and falls back to the backend message.

**Next 16, not the Next you know.** `proxy.ts` replaced `middleware.ts`; Cache Components
(`use cache`) replace `dynamic` and `revalidate`; `params` and `searchParams` are Promises.
The docs in `web/node_modules/next/dist/docs/` match our version and win over memory.

Every page URL carries a locale (`/ko/login`). `LocaleLink` and `localePath()`, never a bare
`/wallet`: a hardcoded path drops the locale and the proxy redirects the user back through it.
`NEXT_PUBLIC_*` values are inlined into the client bundle and are not a security boundary;
`lib/env.ts` throws on boot if one is missing.
