# minidoodle

A "mini Doodle" meeting-scheduling backend: users define available time slots on a personal
calendar, propose meetings against other users, and a meeting confirms once every required
participant votes yes — booking each participant's free slots automatically. Built with Spring Boot
4.1 and Java 21.

Reviewing this against the original brief? [`requirements-mapping.md`](requirements-mapping.md)
traces every requirement to its implementation, with the decisions and trade-offs behind each.

## What it does

- **Time slot management** — create slots with client-chosen start and end times (each slot is
  whatever time frame the user says it is), modify or delete them, mark them busy/free. A
  configurable time grid (`scheduling.time-grid-minutes`, default 30) validates every boundary —
  on a 5-minute grid, `09:05` is a valid boundary and `09:12` isn't. See
  [`design-decisions-v7.md`](design-decisions-v7.md).
- **Meeting scheduling** — propose a meeting with a title, description, and participants (each with
  a role — organizer / required / optional); once every required participant votes yes, the meeting
  confirms and books each participant's free slots for the window automatically.
- **Calendar** is a domain concept only — it's never exposed as its own REST resource; slots are
  addressed through `/api/users/{userId}/slots`.
- **Querying availability** — filter a user's slots by status and/or time range (optional query
  parameters on the slot list endpoint), or find windows where several users are simultaneously
  free via `GET /api/meetings/availability`.
- Designed with "hundreds of users, thousands of slots" in mind: an index on `(calendar_id,
  start_time)`, row-level locking sized to avoid serializing unrelated writes (see below), and a
  concurrency test that actually proves it rather than just asserting it in a docstring.

## Domain

```
User  1 ──── 1  Calendar  1 ──── N  Slot  N ──── M  Meeting
                                                       │
                                              1 ──── N  MeetingParticipant
                                                       │
                                              N ──── 1  User
```

- `User` cascades `ALL` to its `Calendar`, which cascades `ALL` to its `Slot`s. Always persist
  through `userRepository.save(user)` when the user is new — saving from the `Calendar` or `Slot`
  side does not cascade upward and throws `TransientPropertyValueException`.
- **Slots have client-chosen durations; the time grid only validates boundaries.**
  `scheduling.time-grid-minutes` (default 30, `TimeGridConfig`) requires every client-supplied
  boundary — slot start/end, meeting start/end, availability range — to be a grid multiple
  (`epochSecond % (gridMinutes * 60) == 0`). The grid never derives or rewrites stored data, so
  changing the parameter can't corrupt existing rows: loosening it keeps everything valid,
  tightening it only gates future writes ([`design-decisions-v7.md`](design-decisions-v7.md)).
- A `Slot` can belong to several `PROPOSED` meetings at once, but at most one `CONFIRMED` one — that
  constraint isn't enforced at the DB level (the `slot_meeting` join table has no such check);
  `MeetingService.confirm()` enforces it in code, by only ever calling `Meeting.addSlot()` on slots
  it already confirmed are `FREE`.
- A `Meeting` starts `PROPOSED` with no slots booked. Each participant is a `MeetingParticipant`
  with a `ParticipantRole` (`ORGANIZER` / `REQUIRED` / `OPTIONAL`) and a `Vote`
  (`PENDING` / `YES` / `NO`) — the organizer's vote is implicitly `YES` from creation. Once every
  `REQUIRED` participant has voted `YES`, the meeting confirms and books whichever participants have
  a full, contiguous `FREE` cover of `[startTime, endTime)` — see
  [`design-decisions-v2.md`](design-decisions-v2.md) for why a participant *without* that coverage
  doesn't block confirmation for everyone else. A `REQUIRED` participant voting `NO` cancels the
  meeting immediately.
- `Slot` carries an optimistic-locking `@Version`; two requests racing to book the same slot get a
  409 from the second writer.
- `SlotService.create()`/`update()` take a `PESSIMISTIC_WRITE` lock on the *parent* `Calendar` row
  (`CalendarRepository.findByOwnerIdForUpdate`) rather than on individual `Slot` rows — a row-level
  lock on existing rows can't close a phantom-read gap for a brand-new `INSERT`, so the overlap
  check and the write are serialized per user's calendar instead. `SlotService.create()` builds each
  new `Slot` via a `Slot(Calendar, Instant, Instant)` constructor that sets the FK directly, rather
  than going through `Calendar.addSlot()` — the latter mutates (and thus forces a full load of)
  `Calendar.slots`, which would mean reloading every existing slot for that user on every creation.
  See [`spec-review.md`](spec-review.md#2-toctou-race-between-the-overlap-check-and-the-insertupdate)
  for how this was originally found and verified.

## API routes

One `@RestController` per aggregate —
[`UserController`](src/main/java/io/irn/minidoodle/web/UserController.java),
[`SlotController`](src/main/java/io/irn/minidoodle/web/SlotController.java),
[`MeetingController`](src/main/java/io/irn/minidoodle/web/MeetingController.java) — with all
error responses mapped centrally to RFC 9457 `ProblemDetail` by
[`GlobalExceptionHandler`](src/main/java/io/irn/minidoodle/web/GlobalExceptionHandler.java).

```
GET    /api/users                                  → list users (paginated)
GET    /api/users/{userId}                         → get user
POST   /api/users                                  → create user (also creates their calendar)

GET    /api/users/{userId}/slots                   → list slots (optional status/from/to filters; paginated)
POST   /api/users/{userId}/slots                   → bulk-create slots (slots[{startTime,endTime}] in body)
GET    /api/users/{userId}/slots/{slotId}          → get slot
PATCH  /api/users/{userId}/slots/{slotId}          → update slot (startTime, endTime, status)
DELETE /api/users/{userId}/slots/{slotId}          → delete slot

POST   /api/meetings                                         → propose a meeting (PROPOSED)
GET    /api/meetings/{meetingId}                              → get meeting
DELETE /api/meetings/{meetingId}                              → cancel meeting (organizer only)
POST   /api/meetings/{meetingId}/participants/{userId}/vote  → cast a vote
GET    /api/meetings/availability                             → find free windows across users (userIds, from, to params)
```

Every path variable and request body is validated before it reaches business logic — a bad-typed id
or a malformed/mistyped body returns 400 with a specific message, not a 500. See
[`design-decisions-v3.md`](design-decisions-v3.md) and the "Input validation" section of
[`api-examples.md`](api-examples.md).

Every list/query endpoint is paginated: `?page=0&size=20` by default (size capped at 100), returning
`{content, page, size, totalElements, totalPages}` rather than a bare array — see
[`design-decisions-v4.md`](design-decisions-v4.md).

## Run

No Maven wrapper is checked into this repo — use a local Maven install (or your IDE's bundled one)
with a **JDK 21** toolchain (Lombok's annotation processing does not currently work with newer JDKs
such as 26 — getters/builders/`@Slf4j` silently fail to generate, which shows up as a wall of
"cannot find symbol" compile errors).

```bash
# Without Docker (H2 in-memory) — schema generated by Hibernate on startup
mvn spring-boot:run -Dspring-boot.run.profiles=local

# With Docker (PostgreSQL) — includes the app and its database, no extra setup.
# Schema is applied by Flyway (src/main/resources/db/migration); see design-decisions-v4.md.
docker-compose up
```

App starts on `http://localhost:8080` and seeds two users (Alice and Bob) with a few grid-aligned
slots each, in the same time window — check the logs for their generated `userId`s.

## Consume

- [`api-examples.md`](api-examples.md) — a full set of copy-pasteable `curl` examples covering
  users, slots (bulk create, all filter variants, the overlap-conflict case), input validation, and
  the full meeting propose → vote → confirm/cancel flow.
- [`demo.sh`](demo.sh) — a runnable, self-contained walkthrough of the same flow end-to-end against
  a live instance (`./demo.sh`, requires `curl` + `jq`); prints every request and response as it goes.
- Swagger UI at `/swagger-ui.html` documents all routes.
- Metrics: Prometheus-formatted metrics (including request latency percentiles) at
  `/actuator/prometheus`; health at `/actuator/health`.

```bash
# List all slots (paginated — defaults to page=0&size=20)
curl http://localhost:8080/api/users/1/slots

# Filter by status and/or time range, second page of 10
curl "http://localhost:8080/api/users/1/slots?status=FREE&page=1&size=10"

# Bulk-create slots — each with its own client-chosen start/end (boundaries must sit on the grid)
curl -X POST http://localhost:8080/api/users/1/slots \
  -H "Content-Type: application/json" \
  -d '{"slots":[{"startTime":"2027-01-01T09:00:00Z","endTime":"2027-01-01T09:30:00Z"},
               {"startTime":"2027-01-01T10:00:00Z","endTime":"2027-01-01T12:00:00Z"}]}'
```

The filter params (`status`, `from`, `to`) are all optional — with none present the endpoint simply
lists everything. A malformed filter value (bad date, unknown status) is a 400, never silently
ignored.

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
| Unit (Mockito, no Spring context) | `SlotServiceTest`, `MeetingServiceTest` |
| Repository (`@DataJpaTest`) | `SlotRepositoryTest`, `CalendarRepositoryTest` |
| Integration (`@SpringBootTest`, `RANDOM_PORT`, H2) | `UserRouteIT`, `SlotRouteIT`, `MeetingRouteIT` |

123 tests total. Integration tests share seeding/cleanup helpers from
[`TestSupport`](src/test/java/io/irn/minidoodle/TestSupport.java) rather than a common base
class — each IT class carries its own `@SpringBootTest`/`@AutoConfigureTestRestTemplate` setup.

`SlotRouteIT.createSlots_concurrentOverlappingRequests_onlyOneSucceeds` is worth calling out
specifically: it's a real concurrency test, not a unit test with mocked repositories — 8 threads
race over real HTTP to bulk-create the exact same slot, synchronized with a `CountDownLatch`,
asserting exactly one `201` and seven `409`s. It's what actually proves the `PESSIMISTIC_WRITE`
locking in `SlotService` works, rather than just compiling.

The full flow (bulk create, slot filtering, and propose → vote → confirm → cancel) is also
smoke-tested against a real `docker-compose` Postgres instance, not just H2 — see
[`design-decisions-v2.md`](design-decisions-v2.md) for why that matters for this codebase
specifically (H2 and Postgres disagree on how they type-check certain bind parameters).

## Known limitations / trade-offs

Flagged deliberately rather than left for a reviewer to discover — the brief invites shipping an
incomplete solution as long as the reasoning is explained:

- **No authentication/authorization** — every endpoint trusts the `userId` supplied in the path or
  body as-is (e.g. cancelling a meeting only checks that the *supplied* `userId` matches the
  organizer, not that the caller has proven they are that user). Out of scope for this brief, but a
  real gap if this were exposed beyond a trusted network.

(Pagination and schema migrations were both flagged here too until
[`design-decisions-v4.md`](design-decisions-v4.md) addressed them; cross-participant availability
was flagged until [`design-decisions-v5.md`](design-decisions-v5.md) did.)

## Design decisions & how this was validated

A set of decision logs records how this implementation evolved and why, each covering a separate
pass — they intentionally aren't merged into one:

- [`spec-review.md`](spec-review.md) — the original spec-compliance pass against the take-home
  brief: dead bean validation, a TOCTOU race in slot creation, meetings not exposing participants,
  an O(n) collection load on every slot create, and the tests added to prove each fix.
- [`design-decisions-v2.md`](design-decisions-v2.md) — the refactor to the current
  propose/vote/confirm meeting model with system-wide slot duration and bulk slot creation,
  including a contradiction in the brief that had to be resolved (what happens when a participant
  lacks free slots at confirmation time) and the reasoning behind the choice made.
- [`design-decisions-v3.md`](design-decisions-v3.md) — the rebrand to `minidoodle` and an
  input-validation hardening pass closing a real bug (a bad-typed path parameter 500ing instead of
  400ing).
- [`design-decisions-v4.md`](design-decisions-v4.md) — pagination on every list/query endpoint (and
  why it needed a two-query approach, not just an `@EntityGraph` on a `Pageable` query) and Flyway
  for the docker-compose/Postgres profile, with migrations reconstructed from the actual schema
  history rather than a single flattened snapshot.
- [`design-decisions-v5.md`](design-decisions-v5.md) — the cross-participant availability search
  (`GET /api/meetings/availability`): the one piece of core Doodle behavior (suggest a time that
  works, instead of requiring one already picked) that was otherwise missing, built by reusing an
  existing per-user free-slot query across multiple users instead of adding new domain state.
- [`design-decisions-v6.md`](design-decisions-v6.md) — the conversion of the web layer from
  WebMvc.fn functional routes to standard `@RestController` classes, deleting the hand-rolled
  validation/exception-handling infrastructure the functional style had required, with the API
  contract held fixed by the integration tests.
- [`design-decisions-v7.md`](design-decisions-v7.md) — per-slot durations on a validation-only
  time grid: why "create time slots with configurable duration" means the user picks each slot's
  start and end, and why the grid parameter validates new writes instead of deriving domain data
  (so changing it can never corrupt existing rows).

How we work — architecture, conventions, and the principles behind all of the above — is written up
declaratively in [`conventions.md`](conventions.md), and
[`requirements-mapping.md`](requirements-mapping.md) ties each of the brief's requirements to the
relevant implementation and decision log.

(The original take-home prompt isn't included in this repo, since take-home exercises are typically
not meant to be republished — its requirements are summarized in `spec-review.md`.)
