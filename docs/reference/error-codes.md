# Error codes

Each app owns its codes, in `common/errors/*ErrorCode.java`. spot-open-api's are a public
promise; web-bff's are internal. **Never renumber an existing code, and never share an enum
between apps.** A retired code (5019) keeps its number unused forever.

Ranges, so a number hints at where it came from:

| Range | Meaning |
|---|---|
| 1000s | request shape: validation, a body that is not JSON, an unknown path or verb |
| 2000s | auth: credentials, sessions, keys, the trading gate |
| 3000s | rate limiting |
| 4000s | wallet |
| 5000s | orders |
| 6000s | market data over REST |
| 7000s | WebSocket, a transport with no status line |
| 8000s | copy trading |
| 9000s | the catch-all: anything here is a bug, not a user mistake |

Two apps may each use the 5000s, and where a domain exception means the same thing in both they
use the same number by convention (`ORDER_NOT_FOUND` is 5015 in both). That is a courtesy to
whoever reads two logs, not a shared enum.

**A shared service owns no code.** `libs/domain/*` throws plain unchecked exceptions
(`OrderNotFoundException`), and each app's `OrderExceptionAdvice` maps them to its own codes by
type. Returning a sealed result type instead was tried and reverted. Two copies of a security
rule drift apart; two copies of a mapping table do not, and a test in each app checks every
handled exception has a code.

Request fields that need their own code (`side`, `orderType`, `timeInForce`,
`selfTradePreventionMode`) are bound as strings and parsed by `OrderRequestFields`, because
Jackson's own failure is one generic error for all of them. Anything else unreadable is `1000`
from the shared handler; a bug is `9000` and a logged stack trace.

Changed a spot-open-api code, endpoint, field or channel? Update `api-docs/` in the same commit.
Nothing checks this automatically.
