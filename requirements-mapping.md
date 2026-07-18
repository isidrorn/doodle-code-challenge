# Requirements mapping

A requirement-by-requirement account of how the take-home brief is implemented, and where the
decisions and trade-offs behind each piece are recorded. The brief itself isn't republished in
this repo (take-home exercises generally aren't meant to be) — requirements are quoted here only
as short fragments, the same convention [`spec-review.md`](spec-review.md) uses. For *how* this
codebase is built in general (architecture, conventions, principles), see
[`conventions.md`](conventions.md); this file is only about *what was asked and where it lives*.

---

## Time slot management

> "create available time slots with configurable duration in calendars, delete or modify existing
> time slots, and mark time slots as busy or free"

- **Create, with configurable duration** — `POST /api/users/{userId}/slots` bulk-creates slots,
  each with its own client-chosen `[startTime, endTime)`: the user decides when a slot starts and
  ends, and that whole time frame is *a* slot. A configurable time grid
  (`scheduling.time-grid-minutes`, default 30 — `TimeGridConfig`) validates every boundary:
  on a 5-minute grid `09:05` is accepted, `09:12` is a 400.
  **Decision & trade-off**: an earlier iteration read "configurable" as a system-wide fixed slot
  size with `endTime` derived server-side; that was reversed because it both under-delivered the
  requirement and made the parameter dangerous (a config change could invalidate stored rows).
  The grid now validates new writes only and never derives data, so changing it cannot corrupt
  existing rows — the full argument is [`design-decisions-v7.md`](design-decisions-v7.md).
- **Delete / modify** — `DELETE` and `PATCH /api/users/{userId}/slots/{slotId}`. `PATCH` semantics:
  `startTime` alone shifts the slot preserving its length, `endTime` alone resizes it, both
  together define a new interval. A slot booked into a `CONFIRMED` meeting refuses mutation with
  409; a slot merely in `PROPOSED` meetings stays freely editable (nothing has reserved it yet).
- **Busy / free** — `PATCH` with `{"status": "FREE"|"BUSY"}`. A status-only PATCH deliberately
  skips time validation, so tightening the grid never locks users out of their existing slots.
- **Integrity under concurrency** — overlap checking and insertion are serialized per calendar via
  a `PESSIMISTIC_WRITE` lock on the parent `Calendar` row (a row lock on existing slots can't
  close the phantom gap for a new `INSERT`). **Trade-off** (flagged in
  [`spec-review.md`](spec-review.md), issue 2): the lock is coarse — all of one user's slot writes
  serialize against each other — accepted at this scale over Postgres-only exclusion constraints.
- **Proven by**: `SlotServiceTest`, `SlotRouteIT` (including an 8-thread race over real HTTP
  asserting exactly one `201`), `SlotRepositoryTest`.

## Meeting scheduling

> "convert available slots into meetings, add meeting details such as title, description, and
> participants"

- `POST /api/meetings` proposes a meeting (title, description, organizer, required/optional
  participants) in `PROPOSED` state with nothing booked. Participants vote
  (`POST .../participants/{userId}/vote`); a REQUIRED participant voting NO cancels immediately;
  once every REQUIRED participant has voted YES the meeting confirms and books each participant's
  FREE slots covering the window — this is where available slots are "converted into" a meeting
  (marked BUSY and associated via the `slot_meeting` join table). Cancelling
  (`POST /api/meetings/{meetingId}/cancel`, organizer only → 403 otherwise) releases booked
  slots — a POST action rather than DELETE, since nothing is deleted (the meeting transitions to
  CANCELLED and stays retrievable) and RFC 9110 gives a DELETE request body no semantics.
- **Decision & trade-off, silent skip**: the planning brief for this model contradicted itself on
  what happens when a participant lacks free coverage at confirmation time (409-and-block vs.
  skip-and-confirm). The skip version won — a participant already booked elsewhere shouldn't
  permanently block everyone else — documented with both sides in
  [`design-decisions-v2.md`](design-decisions-v2.md).
- **Decision, whole-slot booking**: meetings book whole slots, and only slots *fully contained* in
  the meeting window; with client-chosen durations a slot overshooting the window is skipped
  rather than partially booked (slot splitting is deliberately out of scope) —
  [`design-decisions-v7.md`](design-decisions-v7.md).
- **Decision, code-level invariant**: "a slot is in at most one CONFIRMED meeting" is enforced in
  `MeetingService.confirm()` (which only books slots it verified are FREE), not as a DB
  constraint — simpler, and the confirm path is already serialized by its transaction.
- **Proven by**: `MeetingServiceTest`, `MeetingRouteIT` (full propose → vote → confirm → cancel
  lifecycle, including the missing-coverage participant case).

## Calendar as a domain-only concept

> "Calendar as the term in the task should be present only in the domain in the service"

Implemented literally: `Calendar` is a JPA entity (`User 1──1 Calendar 1──N Slot`) that never
appears as a REST resource — slots are addressed through `/api/users/{userId}/slots`, and no
response DTO exposes a calendar id. The cascade design around it (persist through the `User`
aggregate) is documented in [`CLAUDE.md`](CLAUDE.md) and `spec-review.md`.

## Querying free/busy slots, aggregated view for a time frame

> "querying free or busy slots, providing an aggregated view for a selected time frame"

- **Per user** — `GET /api/users/{userId}/slots?status=&from=&to=`: every filter optional, absent
  means "no filter on that dimension"; a malformed filter value is a 400, never silently ignored
  (an unfiltered result pretending to be filtered is worse than an error).
- **Across users** — `GET /api/meetings/availability?userIds=&from=&to=` returns, for every grid
  window in the range, which of the requested users are free — the "suggest a time that works"
  behavior a scheduling product actually needs. **Decisions** ([`design-decisions-v5.md`](design-decisions-v5.md),
  reworked for variable-length slots in v7): returns *who's free per window* rather than
  collapsing to "windows where everyone is free" (required-vs-optional is the caller's concern);
  the range is capped at 2,000 windows so one request can't drive unbounded server-side work.

## Persistence

> "All data should be persisted to allow for proper management and querying"

JPA/Hibernate over PostgreSQL (docker-compose profile) with Flyway-owned schema migrations and
`ddl-auto: validate`; H2 in-memory for tests/local. **Decisions**: migrations were retrofitted
from the real schema history and say so openly; Postgres/H2 behavioral divergence (bind-parameter
typing) is handled with explicit casts and a smoke-test-on-Postgres rule —
[`design-decisions-v4.md`](design-decisions-v4.md) and the JPQL notes in `CLAUDE.md`.

## Scale

> "hundreds of users with thousands of slots — strive to design your solution according to that"

- Every list endpoint is paginated (`page`/`size`, capped, envelope response) — and paginated *in
  SQL*: the slot search is deliberately two queries because a fetch join plus LIMIT/OFFSET would
  silently paginate in memory ([`design-decisions-v4.md`](design-decisions-v4.md)).
- Index on `slot(calendar_id, start_time)` behind the overlap/range queries.
- Locking is scoped per calendar so unrelated users never serialize against each other
  ([`spec-review.md`](spec-review.md), issue 2 — including why not finer).
- Hot paths avoid O(n) collection loads (a slot insert doesn't load the user's existing slots —
  `spec-review.md`, issue 4).
- The availability walk is bounded (window cap) and does one query per requested user.

## Instructions and "plus" items

- **Runnable with docker-compose, dependencies included** — `docker-compose up` starts PostgreSQL
  (healthcheck-gated) and the app via a multi-stage `Dockerfile`; no other setup.
- **Document how to consume the service** — [`README.md`](README.md) (run/consume/test),
  [`api-examples.md`](api-examples.md) (copy-pasteable curl for every route including error
  cases), [`demo.sh`](demo.sh) (scripted end-to-end walkthrough), Swagger UI at
  `/swagger-ui.html`.
- **Metrics (plus)** — Micrometer + Prometheus at `/actuator/prometheus`, health at
  `/actuator/health`.
- **Tests (plus)** — 127 tests across three layers (unit / `@DataJpaTest` / full-HTTP
  `@SpringBootTest`), including a real 8-thread concurrency test; see README's test section. A
  GitHub Actions workflow runs the full suite on every push (badge in the README).
- **Regular, meaningful commits** — the git history is the development record, kept honest
  (including passes that reversed earlier decisions); each significant pass also has a durable
  decision log (`spec-review.md`, `design-decisions-v2..v7.md`) so the reasoning outlives the
  diffs.
- **"Design and tech decision making" / "incomplete is fine if explained"** — the decision-log
  series exists precisely for this; known gaps are stated rather than left to be discovered, see
  README's *Known limitations* (currently: no authentication/authorization — every endpoint
  trusts the `userId` it's given).
