# URL Shortener Service

Spring Boot 3 / Java 21 service that creates short links, redirects, and reports click analytics.

## Run locally

```bash
./gradlew :url-shortener-service:bootRun        # in-memory H2, http://localhost:8080
open http://localhost:8080/swagger-ui.html      # interactive API docs
```

With PostgreSQL (see `docker-compose.yml` at the repo root):

```bash
docker compose up -d postgres
SPRING_PROFILES_ACTIVE=prod DATABASE_URL=jdbc:postgresql://localhost:5432/shortener \
DATABASE_USERNAME=shortener DATABASE_PASSWORD=shortener ./gradlew :url-shortener-service:bootRun
```

## API

| Method | Path | Description | Responses |
|---|---|---|---|
| `POST` | `/api/v1/links` | Create a link. Body: `{"url", "alias"?, "expiresAt"?}`. Optional `Idempotency-Key` header. | 201, 400, 409, 429 |
| `GET` | `/{code}` | Redirect to the target | 302, 404, 410 |
| `GET` | `/api/v1/links/{code}` | Link metadata | 200, 404 |
| `GET` | `/api/v1/links/{code}/stats` | Total clicks, clicks per day (30 days), top referrers | 200, 404 |
| `DELETE` | `/api/v1/links/{code}` | Disable a link (requires `X-API-Key`) | 204, 403, 404 |

Errors are [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) problem documents.

```bash
curl -s -X POST localhost:8080/api/v1/links -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: 6f1c2a' -d '{"url":"https://example.com/docs"}'
# {"code":"aZ3k9Qx","shortUrl":"http://localhost:8080/aZ3k9Qx","targetUrl":"https://example.com/docs",...}

curl -si localhost:8080/aZ3k9Qx | grep -i location
# Location: https://example.com/docs
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `APP_BASE_URL` | `http://localhost:8080` | Public base for `shortUrl` (its host is also blocked as a target to prevent loops) |
| `APP_ADMIN_API_KEY` | _(empty)_ | Enables `DELETE` when set (≥16 chars); empty = management disabled |
| `APP_RATE_LIMIT_CREATE` / `APP_RATE_LIMIT_REDIRECT` | `30` / `600` | Requests per minute per client IP |
| `APP_DEFAULT_TTL` / `APP_MAX_TTL` | `0s` / `3650d` | Default and maximum link lifetime |
| `APP_ANALYTICS_RETENTION_DAYS` | `180` | Click events older than this are purged nightly |
| `APP_BLOCKED_HOSTS` | _(empty)_ | Comma-separated destination hosts to refuse (subdomains included) |

## Design notes

- **Codes**: 7-char Base62 from `SecureRandom` (≈3.5×10¹² space, not enumerable); collision → retry.
- **Redirects** are `302` + `Cache-Control: no-store` so expiry/disable take effect and every click counts.
- **Hot path**: Caffeine cache of an immutable `LinkTarget` projection (5 min TTL); click analytics are written asynchronously on a bounded executor that drops events under overload rather than slowing redirects.
- **Safety**: only `http(s)`; no embedded credentials; loopback/private/link-local IP literals and `localhost`/`.internal` hosts rejected; own host rejected.
- **Privacy**: click events store only the referrer *host* — no IP addresses or user agents.
- **Schema** is owned by Flyway (`ddl-auto=validate`); migrations are immutable once released.
- **Layering** (api → service → repo → domain) is enforced by an ArchUnit test.

## Tests

```bash
./gradlew :url-shortener-service:test
```

Unit tests (validator, code generator), MockMvc integration tests over the full HTTP surface, a rate-limit test,
architecture rules, and a PostgreSQL migration test via Testcontainers (skipped automatically without Docker).
