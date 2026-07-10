# spring-boot-http-query-verb-example

Two things in one repo:

1. A playground for the new HTTP `QUERY` method
   ([draft-ietf-httpbis-safe-method-w-body](https://www.ietf.org/archive/id/draft-ietf-httpbis-safe-method-w-body-09.html))
   in a Spring Boot 4.1 / Java 21 service.
2. A small "mini Doodle" meeting-scheduling API that gives `QUERY` something real to filter:
   users book `FREE` slots on their calendar and convert them into meetings with other users.

## Why QUERY?

`GET` cannot carry a body (by convention, and many proxies strip it). `POST` carries a body but is
neither safe nor idempotent. `QUERY` fills that gap: a **safe, idempotent, cacheable** read
operation that needs a structured filter in the request body — exactly the case of "search my
calendar slots between these dates, with this status".

## Domain

```
User 1──1 Calendar 1──N Slot N──1 Meeting
```

- `Calendar` is domain-only — it's never exposed as a REST resource; slots are addressed through
  `/api/users/{userId}/slots`.
- `User` cascades `ALL` to its `Calendar`, which cascades `ALL` to its `Slot`s. Always persist
  through `userRepository.save(user)` when the user is new — saving from the `Calendar` or `Slot`
  side does not cascade upward and throws `TransientPropertyValueException`.
- `Slot` carries an optimistic-locking `@Version`; two requests racing to book the same slot get a
  409 from the second writer.
- `MeetingService.schedule()` additionally takes a `PESSIMISTIC_WRITE` lock on every slot involved,
  to close the gap between the availability check and the status update.
- `SlotService.create()`/`update()` take the same kind of lock, but on the *parent* `Calendar` row
  (`CalendarRepository.findByOwnerIdForUpdate`) rather than on individual `Slot` rows — a row-level
  lock on existing rows can't close a phantom-read gap for a brand-new `INSERT`, so the overlap
  check and the write are serialized per user's calendar instead. `SlotService.create()` builds the
  new `Slot` via a `Slot(Calendar, Instant, Instant)` constructor that sets the FK directly, rather
  than going through `Calendar.addSlot()` — the latter mutates (and thus forces a full load of)
  `Calendar.slots`, which would mean reloading every existing slot for that user on every single
  creation. See [`spec-review.md`](spec-review.md#2-toctou-race-between-the-overlap-check-and-the-insertupdate)
  for how this was found and verified.
- `SlotResponse` carries the owning `userId` (`slot.getCalendar().getOwner().getId()`), so a
  scheduled meeting's participants are identifiable from `MeetingResponse.slots[].userId` — both
  `Slot.calendar` and `Calendar.owner` are default-`EAGER` `@ManyToOne`/`@OneToOne`, so this doesn't
  add a query.

## How QUERY is wired

Spring's `HttpMethod` is an open value object (`HttpMethod.valueOf("QUERY")`), so routing on it
doesn't require a custom annotation or touching `RequestMappingHandlerMapping`. This project uses
**Functional Route Definitions** (`WebMvc.fn`) instead of `@RestController`:

```java
route()
    .GET(SLOTS,  accept(APPLICATION_JSON), slots::listAll)
    .route(method(QUERY).and(path(SLOTS)).and(accept(APPLICATION_JSON)), slots::query)
    .POST(SLOTS, contentType(APPLICATION_JSON), slots::create)
    .build()
    .filter(routerExceptionFilter::filter);
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

`@RestControllerAdvice` does not intercept exceptions thrown from a `HandlerFunction` (it only
targets `HandlerMethod`/`@Controller`), so error handling is done by a `HandlerFilterFunction`
chained onto the `RouterFunction` instead — see
[`RouterExceptionFilter`](src/main/java/dev/isidro/queryverb/web/RouterExceptionFilter.java).

Embedded Tomcat accepts `QUERY` on the wire; this is verified end-to-end with `TestRestTemplate`
(a real socket, not MockMvc) — see [`SlotRouteIT`](src/test/java/dev/isidro/queryverb/web/SlotRouteIT.java).

Note: some HTTP clients (including `TestRestTemplate` in this project's own tests) don't set a
`Content-Length` header for a body sent with a non-standard method like `QUERY`. Don't gate body
parsing on `Content-Length` being present — just attempt to parse the body and treat a genuinely
empty/absent one as "no filter" via the parse failure itself.

## Validation

Functional routes have no equivalent of `@Valid` on a `@RequestBody` parameter — `ServerRequest
.body(Class)` never invokes a `Validator`, so the `@NotBlank`/`@NotNull`/`@Email`/`@NotEmpty`
annotations on `UserCreateRequest`, `SlotCreateRequest`, and `MeetingCreateRequest` would otherwise
never be checked, even with `spring-boot-starter-validation` on the classpath. Instead,
[`RequestValidator`](src/main/java/dev/isidro/queryverb/web/RequestValidator.java) parses the body
and runs it through the autoconfigured `jakarta.validation.Validator` explicitly, throwing a
`ResponseStatusException(BAD_REQUEST, ...)` on the first set of violations — which
`RouterExceptionFilter` turns into a ProblemDetail like any other. Every handler that parses a
validated DTO calls `requestValidator.parseAndValidate(request, X.class)` instead of
`request.body(X.class)` directly.

## API routes

All routes are declared in
[`SlotRouterConfig`](src/main/java/dev/isidro/queryverb/web/SlotRouterConfig.java):

```
GET    /api/users                                  → list users
GET    /api/users/{userId}                         → get user
POST   /api/users                                  → create user (also creates their Calendar)

GET    /api/users/{userId}/slots                   → list all slots
QUERY  /api/users/{userId}/slots                   → filter slots (status, from, to in body)
POST   /api/users/{userId}/slots                   → create slot
GET    /api/users/{userId}/slots/{slotId}          → get slot
PATCH  /api/users/{userId}/slots/{slotId}           → update slot (status, times)
DELETE /api/users/{userId}/slots/{slotId}           → delete slot

POST   /api/users/{userId}/slots/{slotId}/meeting   → convert slot into a meeting
DELETE /api/users/{userId}/slots/{slotId}/meeting   → cancel meeting (frees all participant slots)
GET    /api/meetings/{meetingId}                    → get meeting
```

## Run

No Maven wrapper is checked into this repo — use a local Maven install (or your IDE's bundled one)
with a **JDK 21** toolchain (Lombok's annotation processing does not currently work with newer JDKs
such as 26 — getters/builders/`@Slf4j` silently fail to generate, which shows up as a wall of
"cannot find symbol" compile errors).

```bash
# Without Docker (H2 in-memory)
mvn spring-boot:run -Dspring-boot.run.profiles=local

# With Docker (PostgreSQL)
docker-compose up
```

App starts on `http://localhost:8080` and seeds two users (Alice and Bob) with a few slots each —
check the logs for their generated `userId`s.

## Consume

- [`api-examples.md`](api-examples.md) — a full set of copy-pasteable `curl` examples covering
  users, slots (including all `QUERY` filter variants and the overlap-conflict case), and meetings.
- [`demo.sh`](demo.sh) — a runnable, self-contained walkthrough of the same flow end-to-end against
  a live instance (`./demo.sh`, requires `curl` + `jq`); prints every request and response as it goes.

```bash
# List all slots
curl http://localhost:8080/api/users/1/slots

# QUERY: filter by status and/or time range
curl -X QUERY http://localhost:8080/api/users/1/slots \
  -H "Content-Type: application/json" \
  -d '{"status":"FREE"}'

# Create a slot
curl -X POST http://localhost:8080/api/users/1/slots \
  -H "Content-Type: application/json" \
  -d '{"startTime":"2026-06-01T10:00:00Z","endTime":"2026-06-01T11:00:00Z"}'
```

`SlotQueryFilter` fields (`status`, `from`, `to`) are all optional — an empty/absent body is a valid
QUERY meaning "no filter".

## Tests

```bash
# Unit + repository tests only (default Surefire include pattern: *Test.java)
mvn test

# Everything, including the *IT.java integration tests (no failsafe plugin is configured,
# so *IT classes aren't picked up unless explicitly selected)
mvn test -Dtest=*Test,*IT
```

| Layer | Classes |
|---|---|
| Unit (Mockito, no Spring context) | `SlotServiceTest`, `MeetingServiceTest`, `RequestValidatorTest` |
| Repository (`@DataJpaTest`) | `SlotRepositoryTest`, `CalendarRepositoryTest` |
| Integration (`@SpringBootTest`, `RANDOM_PORT`, H2) | `UserRouteIT`, `SlotRouteIT`, `MeetingRouteIT` |

51 tests total. Integration tests share seeding/cleanup helpers from
[`TestSupport`](src/test/java/dev/isidro/queryverb/TestSupport.java) rather than a common base
class — each IT class carries its own `@SpringBootTest`/`@AutoConfigureTestRestTemplate` setup.

`SlotRouteIT.createSlot_concurrentOverlappingRequests_onlyOneSucceeds` is worth calling out
specifically: it's a real concurrency test, not a unit test with mocked repositories — 8 threads
race over real HTTP to create the exact same overlapping slot, synchronized with a
`CountDownLatch`, asserting exactly one `201` and seven `409`s. It's what actually proves the
`PESSIMISTIC_WRITE` locking in `SlotService` works, rather than just compiling.

## Relationship to the Doodle coding challenge

This repo doubles as the submission for a "mini Doodle" scheduling backend take-home challenge: the
`User`/`Calendar`/`Slot`/`Meeting` domain, the repository query patterns, and the functional-route
style aren't a separate exercise bolted on — this repository *is* the challenge submission, built
around the `QUERY` verb demo rather than alongside it. (The original prompt isn't included in this
repo, since take-home exercises are typically not meant to be republished — but the requirements it
covers are summarized in [`spec-review.md`](spec-review.md).)

See [`spec-review.md`](spec-review.md) for the audit trail of validating this implementation
against the spec: what was checked, what was found wrong (dead bean validation, a TOCTOU race in
slot creation, meetings not exposing participants, an O(n) collection load on every slot create),
exactly what changed to fix each one, and the test coverage that was missing and got added along
the way — including one genuine concurrency test, not just mocked unit tests.
