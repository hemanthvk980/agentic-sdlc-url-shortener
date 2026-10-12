## greenfield-20261012-160221

Layered Spring Boot service (api → service → repository → domain). Random Base62 codes, 302 redirects, asynchronous click recording into a click_event table aggregated per day, Bucket4j-style rate limiting on creation, strict URL validation.

- `POST /api/v1/links`
- `GET /{code}`
- `GET /api/v1/links/{code}/stats`
