# Money is never a float

A float cannot hold 0.1, and a balance that drifts by a satoshi per trade is a reconciliation
failure months later with no line of code to blame. Every amount is an integer count of
1e-8 units, everywhere:

| Where | Type |
|---|---|
| MySQL | `BIGINT`, the value times 1e8. Never `DECIMAL` |
| Java | `long`. `Money` in `libs/common` does the arithmetic; `Money.multiply` unscales once so a product stays 1e8-scaled and checks for overflow, because two scaled prices multiplied wrap `long` silently |
| Protobuf | `int64`, already scaled |
| JSON, both ways | a decimal string: `"63250.00"`, never a JSON number |
| Browser | a decimal string; `lib/money` compares and rounds strings. `parseFloat` only when rendering a percentage or a chart, never for anything compared, summed or sent |

Rounding happens once, at the edge, to the symbol's tick or step (`roundToStep`,
`SpotSymbol.isPriceOnTick`, `isValidQuantity`). Nothing downstream rounds again.
