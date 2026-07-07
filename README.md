# spring-boot-http-query-verb-example

Little project to play around with the new HTTP `QUERY` method
([draft-ietf-httpbis-safe-method-w-body](https://www.ietf.org/archive/id/draft-ietf-httpbis-safe-method-w-body-09.html))
in a Spring Boot 3.4 / Java 21 service.

## Why QUERY?

`GET` cannot carry a body (by convention, and many proxies strip it). `POST` carries a body but is
neither safe nor idempotent. `QUERY` is meant to fill that gap: a **safe, idempotent, cacheable**
read operation that needs a structured filter in the request body — exactly the case of "search my
calendar slots between these dates, with this status".

## Domain

`User` 1—1 `Calendar` 1—N `Slot`. `Calendar` is intentionally never exposed as a REST resource on
its own — it's a domain-only concept, slots are addressed through `/api/calendars/{calendarId}/slots`.

## How QUERY is wired

Spring's `HttpMethod` is an open value object (`HttpMethod.valueOf("QUERY")`), so routing on it
doesn't require a custom annotation or touching `RequestMappingHandlerMapping`. This project uses
**Functional Route Definitions** (`WebMvc.fn`) instead of `@RestController`:

```java
route()
    .GET(SLOTS_PATH, accept(APPLICATION_JSON), handler::listAll)
    .route(method(QUERY).and(path(SLOTS_PATH)).and(accept(APPLICATION_JSON)), handler::query)
    .POST(SLOTS_PATH, contentType(APPLICATION_JSON), handler::create)
    .build();
```

See [`SlotRouterConfig`](src/main/java/dev/isidro/queryverb/web/SlotRouterConfig.java) and
[`SlotHandler`](src/main/java/dev/isidro/queryverb/web/SlotHandler.java).

Other ways this could have been done (and why they were discarded here):
- **Servlet filter rewriting `QUERY` → `POST`**: works on any Spring version but is a workaround,
  hides the real method from logs/metrics/tracing.
- **Custom `RequestCondition` + custom annotation**: closer to `@RestController` ergonomics, but
  needs extra infrastructure (a custom `HandlerMapping`) for a single extra verb.
- **Functional routes (chosen)**: standard Spring API, no custom infra, and it reads cleanly next
  to `GET`/`POST` on the same resource.

Embedded Tomcat (via Spring Boot 3.4's parent) accepts `QUERY` on the wire; this is verified with a
full round-trip test through `TestRestTemplate` (real socket, not MockMvc), not just routing logic.

## Run

```bash
./mvnw spring-boot:run
```

App starts on `http://localhost:8080` and seeds one calendar with a few slots (see logs for the
generated `calendarId`).

## Consume

```bash
# List all slots
curl http://localhost:8080/api/calendars/1/slots

# QUERY: filter by status and time range (most HTTP clients need an explicit method override flag)
curl -X QUERY http://localhost:8080/api/calendars/1/slots \
  -H "Content-Type: application/json" \
  -d '{"status":"FREE"}'

# Create a slot
curl -X POST http://localhost:8080/api/calendars/1/slots \
  -H "Content-Type: application/json" \
  -d '{"startTime":"2025-01-01T10:00:00Z","endTime":"2025-01-01T11:00:00Z"}'
```

`SlotQueryFilter` fields (`status`, `from`, `to`) are all optional — an empty/absent body is a valid
QUERY meaning "no filter".

## Tests

```bash
./mvnw test
```

`SlotQueryRouteIT` exercises the QUERY route end-to-end against the real embedded server.

## Relationship to the Doodle coding challenge

This repo's domain (`User`/`Calendar`/`Slot`), repository query pattern, and functional-route style
are deliberately reusable building blocks for a separate meeting-scheduling (mini Doodle) exercise.
