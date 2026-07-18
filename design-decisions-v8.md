# Design decisions: pre-delivery review hardening (v8)

Companion to [`spec-review.md`](spec-review.md) and `design-decisions-v2..v7.md` — separate
passes, don't merge them. This pass came from a deliberate "take it down as a reviewer" exercise
against the finished submission: every finding below was either fixed or explicitly accepted, and
one fix produced a live-caught migration lesson worth reading on its own.

## 1. The service layer spoke HTTP — fixed

Services threw `ResponseStatusException` (a Spring Web type) 34 times: business logic deciding
HTTP status codes, and a service layer unusable behind any non-HTTP delivery mechanism. Replaced
with a small HTTP-agnostic hierarchy in `io.irn.minidoodle.exception` — `NotFoundException`,
`ConflictException`, `InvalidInputException`, `ForbiddenException` — with
`GlobalExceptionHandler` as the single place mapping each to a status. Unit tests now assert
exception *types*; the integration tests passed unchanged, which is the proof the HTTP contract
didn't move. The advice keeps a `ResponseStatusException` handler purely as a framework safety
net (the catch-all 500 would otherwise flatten framework-thrown instances).

**Deliberately not done**: per-rule exception classes (`SlotOverlapException`,
`MeetingNotProposedException`, ...) — four intent-level types with precise messages carry the same
information to both the client and the advice without a class per rule; and moving request DTOs
out of the service signatures (a command-object layer) — the DTOs are plain records with no web
annotations beyond validation, and a second delivery mechanism would be the trigger to revisit.

## 2. Cancel was DELETE with a request body — fixed

`DELETE /api/meetings/{id}` with `{userId}` in the body was doubly wrong: RFC 9110 gives a DELETE
request body no defined semantics (intermediaries may legitimately drop it), and nothing is
deleted — the meeting transitions to CANCELLED and stays retrievable. Cancel is a state
transition exactly like voting, so it is now `POST /api/meetings/{id}/cancel`. The caller's
`userId` still travels in the body only because the API has no authentication (the documented
limitation); with auth, identity comes from the token and the body disappears entirely.

## 3. Input hardening: length caps and a real unique email — fixed

Two things a reviewer could trigger from Swagger in seconds:

- A 10,000-character name sailed past bean validation (no `@Size`) into the `varchar(255)` column
  and surfaced as a 500 — violating this API's own "predictable bad input never 500s" rule.
  `@Size` caps now match the DB column sizes *exactly*, both tightened together in `V3`:
  name 100, email 254, meeting title 150, description 1000.
- Nothing prevented two users with the same email. Now a genuine DB unique constraint, with a
  service-level pre-check for the friendly 409 message and a
  `DataIntegrityViolationException → 409` mapping as the backstop for the concurrent-create race
  the pre-check alone can't close.

### The migration lesson, caught live

`V3` initially failed against a docker-compose volume that had survived earlier runs:
`could not create unique index — Key (email)=(alice@example.dev) is duplicated`. The cause was
the pre-fix `DataSeeder`, which inserted the seed users unconditionally on **every** startup — so
any database that had ever been restarted contained exactly the duplicates the new constraint
forbids. Two fixes, both verified against that same dirty volume rather than a fresh one:

- `DataSeeder` is now idempotent (`existsByEmail` check before inserting).
- `V3` de-dupes before constraining: the oldest row per email is kept untouched; younger
  duplicates are renamed deterministically (`left(email, 240) || '+dup' || id`, bounded under the
  new 254 limit) rather than deleted — deleting would cascade into calendars, slots, and meeting
  participations that legitimately reference those rows.

The general rule this reinforces: a migration must survive any data that *previously shipped
code* could have produced — testing it only against a fresh schema proves nothing about that.

## 4. CI — added

A GitHub Actions workflow runs the full suite (`mvn -B test -Dtest='*Test,*IT'`, JDK 21 Temurin,
Maven cache) on every push and pull request, with a badge in the README. The explicit test
pattern matters: no failsafe plugin is configured, so plain `mvn test` would silently skip every
`*IT` class and the badge would certify half the pyramid.

## 5. Small finds

- The v7 claim that a status-only PATCH performs no grid validation (so tightening the grid never
  locks users out of existing slots) was documented but untested — now proven by an integration
  test that seeds an off-grid slot directly through the repository and PATCHes its status.
- `DataSeeder`'s startup log said "use the logged **calendar** IDs to call the API" — the API
  takes *user* ids, and `Calendar` is deliberately never exposed. Now says userIds.

## Verified

127 tests green; the full docker-compose Postgres flow re-verified including `V3` over a dirty
volume, the duplicate-email 409 over HTTP, the over-long-name 400, and a container restart
proving seeder idempotency against the new constraint.
