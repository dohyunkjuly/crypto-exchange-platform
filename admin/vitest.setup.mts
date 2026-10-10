import "@testing-library/jest-dom/vitest";

// Vitest does not read .env.local the way Next does, and lib/env.ts throws on a missing value
// by design.
process.env.NEXT_PUBLIC_ADMIN_BFF_URL ??= "http://localhost:8083";
process.env.NEXT_PUBLIC_EXCHANGE_NAME ??= "Crypto Exchange";
