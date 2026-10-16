# Credentials

| Kind | Stored as | Why |
|---|---|---|
| Password | bcrypt, strength 12 | only ever compared, never read back |
| Session token, recovery code | SHA-256 | already 256 bits of randomness; bcrypt on every request would be a self-inflicted DoS |
| API key secret, TOTP seed | AES-256-GCM | must be read back to recompute a signature or a code |

Never store a raw secret. Sessions are **opaque tokens, not JWT**: setting `revoked_at` kills one
instantly, where a JWT would need a blocklist, which is just this table again. A cookie being
present does not mean logged in; only web-bff can tell, and the browser's gate is a client
component that redirects on 401 ([frontend-session.md](frontend-session.md)). The mobile app
holds the same token and sends it as a bearer; a browser is never given it outside the cookie.

**No secret or config value has a default.** `API_KEY_ENCRYPTION_KEY` and `MFA_ENCRYPTION_KEY`
are separate keys on purpose, and the app crashes at startup if one is missing. Never a
placeholder: a placeholder that works in dev ships to production.

A crypto primitive (`AesGcmCipher`, `Totp`, a hash) lives in `libs/common`, knows nothing about
Spring, and takes its key material as a constructor argument. The service that uses it reads
the environment variable itself; there is no `@Configuration` class for it.

Emailed codes are typed codes, never links.
