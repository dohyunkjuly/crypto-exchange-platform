# web

The exchange's browser front end: Next.js 16, React 19, Tailwind 4, TypeScript strict, on :3000.
Markets, the trading screen, orders, wallet and account pages, in English and Korean.

Next renders and does not fetch. Every request goes from the browser to web-bff and nowhere
else, the session is a cookie web-bff owns, and the sign-in gate is a client component that
redirects on 401. Why, and the rules that follow from it:
[frontend-session.md](../docs/reference/frontend-session.md).

`src/app/` is routing only; features live in `src/features/`, each with its components, hooks
and requests; `src/lib/bff` is the one place that knows web-bff exists. The style rules the
code cannot show you: [STYLE.md](../docs/STYLE.md). The Next.js docs that match this version:
`node_modules/next/dist/docs/`.
