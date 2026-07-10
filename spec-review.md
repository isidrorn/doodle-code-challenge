# Spec review: from `coding-challenge.md` to the current implementation

This is the audit trail for validating the "mini Doodle" implementation against
[`coding-challenge.md`](coding-challenge.md). It records what was checked, what was found wrong or
missing, and exactly what changed to fix it — so the reasoning survives past the commit history.
Everything below was verified against a **running instance** (`mvn spring-boot:run
-Dspring-boot.run.profiles=local`, real `curl` requests), not just by reading code or trusting green
tests.

## Spec compliance at the time of review

The implementation already covered the required functionality:

- Domain model (`User 1─1 Calendar 1─N Slot N─1 Meeting`) matches "users manage slots on a personal
  calendar, slots convert to meetings"; `Calendar` correctly never appears as a REST resource.
- Slot CRUD (create/modify/delete, mark busy/free) and meeting scheduling (title/description,
  slot→meeting conversion) were implemented.
- `QUERY` with `{status, from, to}` in the body satisfied "querying free or busy slots ... for a
  selected time frame."
- `docker-compose up` worked end-to-end (Postgres + app, multi-stage Dockerfile, healthcheck-gated
  startup) — satisfying "runnable locally using docker-compose."
- Metrics (Actuator/Prometheus) and a real test suite (unit/repository/integration) covered the two
  "plus" asks.
- Indexing (`slot(calendar_id, start_time)`) and pessimistic + optimistic locking in
  `MeetingService` showed the "hundreds of users, thousands of slots" scale note had already been
  taken seriously in one part of the codebase — just not applied consistently everywhere (see below).

## Issues found and fixed

### 1. Bean validation annotations were declared but never invoked

`SlotCreateRequest`, `MeetingCreateRequest`, and `UserCreateRequest` all carried
`@NotBlank`/`@NotNull`/`@Email`/`@NotEmpty`, and `spring-boot-starter-validation` was on the
classpath — but WebMvc.fn `HandlerFunction`s have no equivalent of `@Valid` on a `@RequestBody`
parameter. `ServerRequest.body(Class)` never runs a `Validator`, so none of these constraints were
ever checked. Confirmed live:

| Request | Expected | Actual (before fix) |
|---|---|---|
| `POST /api/users` with `{"name":"","email":"not-an-email"}` | 400 | **201** |
| `POST /api/users/{id}/slots` with `{"startTime":null,...}` | 400 | **500** (NPE in `SlotService.create`, since it calls `.isAfter()` directly on the null) |
| `POST .../meeting` with `{"participantSlotIds":[]}` | 400 | **201** (a "meeting" with zero participants) |

**Fix**: added `RequestValidator` (`web/RequestValidator.java`) — a small component that parses the
body and runs it through the autoconfigured `jakarta.validation.Validator`, throwing a
`ResponseStatusException(BAD_REQUEST, ...)` on violations (which `RouterExceptionFilter` already
turns into a ProblemDetail). Wired into `SlotHandler.create`, `MeetingHandler.schedule`, and
`UserHandler.create` in place of the raw `request.body(...)` call.

### 2. TOCTOU race between the overlap check and the insert/update

`SlotService.create()`/`update()` called `SlotRepository.existsOverlap(...)` and then saved,
with nothing locking the gap between the two. `MeetingService.schedule()` already takes
`PESSIMISTIC_WRITE` locks specifically "to close the gap between the availability check and the
status update" (per its own doc comment) — the same reasoning just hadn't been carried over to slot
creation/update, so two concurrent requests for overlapping windows on the same calendar could both
pass the check before either committed.

**Fix**: added `CalendarRepository.findByOwnerIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`), acquired at
the top of `SlotService.create()`/`update()` before the overlap check. This serializes slot
writes *per user's calendar* — locking the parent row (not the individual slot rows) is what closes
a phantom-read gap for a brand-new INSERT, which per-row locking on existing rows can't do.

Verified with a real concurrency test, not just by reading the code — see
[`SlotRouteIT.createSlot_concurrentOverlappingRequests_onlyOneSucceeds`](src/test/java/dev/isidro/queryverb/web/SlotRouteIT.java):
8 threads race to create the exact same overlapping slot; exactly one gets `201`, the rest get
`409`. Run 5 times in a row with no flakes.

**Trade-off** (flagged, not resolved): this lock is coarse — it serializes *all* slot writes for a
given user against each other, not just genuinely overlapping ones. Acceptable at "hundreds of
users" scale since a single user issuing many concurrent slot-mutation requests is the uncommon
case, but a finer-grained scheme (e.g. locking only candidate overlapping rows, or a Postgres
exclusion constraint) was considered and rejected — the latter is Postgres-only and the test suite
runs on H2 (see the CLAUDE.md note on Postgres/H2 divergence for optional-filter queries; the same
divergence risk would apply here).

### 3. Meeting responses never exposed participants

`SlotResponse`/`MeetingResponse` carried `id/startTime/endTime/status/meetingId` but no owning
`userId`. Scheduling a meeting and then `GET /api/meetings/{id}` gave no way to tell *who* was in
it — only opaque slot IDs. The spec explicitly lists "participants" as a meeting detail.

**Fix**: added `userId` to `SlotResponse`, populated in `SlotMapper` from
`slot.getCalendar().getOwner().getId()`. Both associations are default-`EAGER`
`@ManyToOne`/`@OneToOne`, so this doesn't add a query. Kept the existing "derive participants from
slots" design (documented in `Meeting.java`) rather than adding a redundant participants list.

Fixing this surfaced a second, unrelated bug: `MeetingRepository.findById()`'s
`@EntityGraph(attributePaths = "slots")` is a JPA **fetch graph**, which silently demotes any
association *not* listed in the graph to `LAZY` — including `Slot.calendar` and `Calendar.owner`,
even though both are `EAGER` by their own mapping. With `open-in-view: false`, that turned into a
`LazyInitializationException` → 500 on `GET /api/meetings/{id}` the moment `SlotMapper` started
reading `userId` outside the transaction. `MeetingRouteIT.getMeeting_returns200_afterScheduling`
caught this on the first post-fix test run. Fixed by extending the graph to
`{"slots", "slots.calendar", "slots.calendar.owner"}`.

### 4. `SlotService.create()` loaded a user's entire slot collection just to append one row

`CalendarRepository.findByOwnerId` is `@EntityGraph(attributePaths = "slots")` — eagerly loading
*every* existing slot for that user, used via `calendar.addSlot(slot)` which mutates (and thus
forces initialization of) that collection. At "thousands of slots per user" this is O(n) work and
memory on every single slot creation, for an association `Slot` already owns via its own FK.

**Fix**: added a `Slot(Calendar, Instant, Instant)` constructor that sets the FK directly. Combined
with fix #2, `SlotService.create()` now uses the already-locked `Calendar` reference from
`findByOwnerIdForUpdate` (which has no entity graph) to build the `Slot` directly, without ever
touching `Calendar.slots`. `Calendar.addSlot()` remains as-is for `DataSeeder`, where loading a
handful of slots at startup is irrelevant.

**Trade-off** (flagged, not resolved): this bypasses `Calendar`'s in-memory `slots` list for this
one call site — harmless today since nothing reads `calendar.getSlots()` later in the same
transaction, but it's a small crack in the "always mutate via `addSlot`" invariant the domain
otherwise maintains everywhere.

## Test coverage audit

Before this pass, all 36 existing tests passed — but none of the four fixes above had any coverage
proving they worked, and a few pre-existing branches were untested too. Added 15 tests (36 → 51),
all verified green, plus 5 repeated runs of the concurrency test specifically to rule out flakiness.

| Gap | Added |
|---|---|
| `RequestValidator` had no direct test | `RequestValidatorTest` — valid passthrough, blank-name rejection, malformed-email rejection |
| No IT proved validation actually returns 400 over HTTP | `UserRouteIT`: blank name, bad email · `SlotRouteIT`: missing `startTime` · `MeetingRouteIT`: blank title, empty `participantSlotIds` |
| `findByOwnerIdForUpdate` had no test at all | `CalendarRepositoryTest` — exists / not-found |
| The overlap-check race fix had no test proving the lock actually serializes writers | `SlotRouteIT.createSlot_concurrentOverlappingRequests_onlyOneSucceeds` (8-thread race, see above) |
| New `userId` field was untouched by any assertion | Added assertions in `SlotRouteIT` (list/get) and `MeetingRouteIT` (schedule response) |
| `SlotService.update()`'s `BAD_REQUEST` (start≥end) and `CONFLICT` (overlap) branches were never exercised by any test, unit or IT — pre-existing gap, unrelated to the fixes above | `SlotServiceTest.update_throwsBadRequest_whenStartAfterEnd`, `update_throwsConflict_whenOverlapExists` · `SlotRouteIT.patchSlot_returns409_whenRescheduleOverlapsAnotherSlot` |
| The QUERY-with-no-body → "no filter" fallback (`SlotHandler.parseFilter`'s catch block) — the exact quirk this whole repo exists to demonstrate — was never tested with a genuinely empty body; every existing QUERY test sent a `SlotQueryFilter` object, not an absent one | `SlotRouteIT.querySlots_withNoBody_treatedAsNoFilter_returnsAllSlots` |

## Discussed, deliberately not changed

- **No pagination** on `GET /api/users` or `GET /api/users/{userId}/slots` — flagged as a scale
  concern given the spec's "hundreds of users, thousands of slots" note, but left out of scope for
  this pass; would need a decision on cursor vs. offset pagination and a response envelope change.
- **Any participant can cancel a meeting for everyone** (`MeetingService.cancel` only checks that
  the caller owns the specific slot in the path, not that they're the original organizer — and
  "organizer" isn't even a stored concept, just whichever slot was passed first to `schedule()`).
  Confirmed as intentional given the "mini Doodle" framing rather than fixed.
