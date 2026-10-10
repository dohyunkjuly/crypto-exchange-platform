import "@testing-library/jest-dom/vitest";

// Vitest does not read .env.local the way Next does, and lib/env.ts throws on a missing URL by
// design. Tests that probe that behaviour stub the variable away themselves (env.test.ts).
process.env.NEXT_PUBLIC_BFF_URL ??= "http://localhost:8081";
process.env.NEXT_PUBLIC_EXCHANGE_NAME ??= "Crypto Exchange";
