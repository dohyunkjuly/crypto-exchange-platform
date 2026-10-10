# Deployment: the edge

One nginx is the only process with public ports. Everything else is reached by compose
service name and publishes nothing, or loopback only for the bots and the integration tests.
The config is [deploy/nginx/templates/exchange.conf.template](../../deploy/nginx/templates/exchange.conf.template);
the staff allowlist is [deploy/nginx/admin-allowlist.conf](../../deploy/nginx/admin-allowlist.conf).

## Four hosts

| Host | Serves | Upstreams |
|---|---|---|
| `app.<domain>` | the exchange | `/` → web, `/bff/` → web-bff (WebSocket upgrades included) |
| `api.<domain>` | the public API | spot-open-api |
| `docs.<domain>` | the API documentation | api-docs, a static image built from `api-docs/content` |
| `admin.<domain>` | the staff console | `/` → admin, `/admin/` → admin-bff, behind the allowlist |

Anything else, a bare IP included, gets connection closed (`return 444`).

**Hosts, not paths.** The page and its BFF share one origin, so there is no CORS, no
`SameSite=None`, and the frontends are built with `NEXT_PUBLIC_*_URL=/` ("the page's own
origin") instead of an address. The API gets an origin no customer cookie is ever sent to. The
staff host can be pointed at an internal address in DNS, on top of the allowlist. And nothing
behind nginx had to learn a path prefix.

Locally the hosts are `app.localhost`, `api.localhost`, `docs.localhost` and `admin.localhost`.
Browsers resolve `*.localhost` to the loopback with no `/etc/hosts` entry, and treat it as a
secure context, which is what lets the `Secure` session cookies work over plain http. (Safari
does not; use Chrome or Firefox for the local stack, or terminate TLS as below.)
`EXCHANGE_DOMAIN` adds the production names beside the local ones, so the same file ships.

## The client's address survives the hop

Every backend reads `request.getRemoteAddr()` for something that matters: web-bff's rate limit
and auth events, the open API's weight limit and **API-key IP allowlists**, admin-bff's audit
rows. Behind a proxy that would be the proxy's address for everyone, and a key allowlist would
refuse every request.

nginx sends `X-Forwarded-For` and `X-Forwarded-Proto`, and the three edge apps run with
`server.forward-headers-strategy: native`: Tomcat's RemoteIp valve rewrites the remote address
and the scheme from those headers, **only when the request came from a private range** (its
default `internal-proxies`), which the compose network is. A header forged from the internet is
ignored. With no proxy at all, a client on the same host could forge one; that is the laptop and
nothing else.

## Staff allowlist

`admin-allowlist.conf` is `allow` lines followed by `deny all`, and it ships with the private
ranges only. In production, replace them with the office and VPN egress addresses. This is the
"internal network behind an IP allowlist" that [admin.md](admin.md) assumes; nginx is where it
is enforced.

A laptop is not always a private address: a VPN or some host networking presents the browser
to Docker from a public one, and the console answers 403 with a line saying so. For that,
`ADMIN_ALLOWLIST=./deploy/nginx/admin-allowlist.local.conf` in `backend/.env` mounts the file that
allows everyone. It is never the right file for a deployment, which is why it is not the default.

## TLS

The template listens on 80. Locally that is the whole story. In production, either:

- **Terminate at your load balancer** (the usual cloud shape) and keep nginx on 80 behind it.
  The balancer must send `X-Forwarded-Proto: https`; the apps already honour it.
- **Terminate in nginx.** Add a 443 server per host with your certificates mounted into the
  container, and redirect 80 to it:

  ```nginx
  server {
      listen 443 ssl http2;
      server_name app.${EXCHANGE_DOMAIN};
      ssl_certificate     /etc/nginx/certs/app.fullchain.pem;
      ssl_certificate_key /etc/nginx/certs/app.key.pem;
      ssl_protocols TLSv1.2 TLSv1.3;
      add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
      location /bff/ { proxy_pass http://web-bff; }
      location /     { proxy_pass http://web; }
  }
  server { listen 80; server_name app.${EXCHANGE_DOMAIN}; return 301 https://$host$request_uri; }
  ```

  Certificates come from wherever you get them (Let's Encrypt with a DNS challenge covers all
  four names in one go). To try the 443 blocks on a laptop, `mkcert "*.localhost"` gives a
  locally trusted certificate; it is optional.

## Kafka: the apps need two values

A deployment runs a managed Kafka (MSK, Confluent, Aiven) or its own cluster; nothing in this
repo deploys brokers. The apps need `KAFKA_BOOTSTRAP_SERVERS` and `KAFKA_REPLICATION_FACTOR=3`:
every topic they create then has three copies and `min.insync.replicas=2` (`libs/common`
`Replication`), and the producers' `acks=all` means a broker can die without a lost order or a
stopped exchange. The replication factor applies when a topic is created; raising it on a
cluster whose topics exist is a partition reassignment with Kafka's own tools. Tiered storage
for the forever topics, disks and monitoring are the operator's broker set-up
([white-label.md](white-label.md)).

A managed cluster usually requires TLS and IAM or SASL on the connection; the local broker is
plaintext, so that is the one Kafka setting a deployment adds, through Spring Kafka's standard
properties as environment (`SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL` and the `SASL_*` ones).
The partition count of `commands.v1` is a capacity decision fixed once a market has traded
([command-partition.md](command-partition.md)); the apps create the topics on first start, or
the operator's tooling does, with the same settings.

The single broker in `docker-compose.yml` is the laptop. `docker-compose.perf.yml`
overlays three containers for the benchmarks only -- the report's second column and the
broker-loss scenario ([performance.md](performance.md)); it is not how a deployment runs Kafka.

## What stays off the internet

- The seven backend ports and the two Next.js ports: not published, or `127.0.0.1` only.
- Kafka, MySQL, MongoDB, Redis, MinIO, Mailpit: the compose file publishes them for local
  development; a deployment does not.
- `admin-bff` has no public route except through `admin.<domain>/admin/`, behind the allowlist.
- Actuator and Swagger on web-bff are not routed by nginx at all.

## Limits in the config, and why

| Directive | Value | Reason |
|---|---|---|
| `client_max_body_size` | 1 MB | nothing here uploads: a KYC form, an order, a settings row |
| `proxy_read_timeout` | 90 s | longer than the sockets' 30 s server ping, so an idle socket is closed by the app with a close frame, not cut by nginx |
| `server_tokens` | off | no version in error pages |
| `X-Frame-Options` | DENY | nothing here is meant to be framed |
