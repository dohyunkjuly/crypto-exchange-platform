# api-docs

The public developer documentation for the spot API: the page an outside developer reads to
learn how to sign a request, place an order or subscribe to a socket. It is our own small
static-site build: Markdown in, one HTML page out.

## Running it

Needs Node 20 or newer.

```sh
export EXCHANGE_NAME="Crypto Exchange"   # the operator's name, in the titles; no default
./build.sh          # renders the site into build/ (gitignored)
./build.sh serve    # renders it, then serves it at http://localhost:4567
```

## Branding it

`content/logo.svg`, `content/favicon.svg` and `content/apple-icon.png` are the operator's; the
ones here are a placeholder brand. `EXCHANGE_NAME` fills the page titles.

## Editing it

- `content/index.md` is the introduction. Each section is a file in `content/includes/`, in
  the order `index.md` lists them.
- `content/log.md` is the change log. A `#` heading is a date; a `##` heading is an entry.
- Code blocks and `> ` quotes appear in the dark right-hand column; everything else on the left.

## The rule

What the pages say must match the code. A new or changed endpoint, field, channel, error code
or limit in spot-open-api is edited in `content/includes/` in the same commit. Nothing checks
this for you ([AGENTS.md](../AGENTS.md)).
