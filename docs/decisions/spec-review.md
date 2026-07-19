# Spec review: validating the implementation against the original brief

This is the audit trail for validating the "mini Doodle" implementation against the backend
take-home brief it was built for (a scheduling platform: users manage time slots on a personal
calendar and convert them into meetings — the original prompt itself isn't included in this repo,
since take-home exercises are typically not meant to be republished). It records what was checked
and what was found wrong or missing, verified against a **running instance**
(`mvn spring-boot:run -Dspring-boot.run.profiles=local`, real `curl` requests), not just by reading
code or trusting green tests.

**This was the first of eight review/design passes** (see `conventions.md`'s "Git and
documentation" section for the full list), audited against the *original* implementation: WebMvc.fn
functional routing and a single-slot "convert one slot into a meeting" model. Both were completely
replaced since — routing by [`design-decisions-v6.md`](design-decisions-v6.md)'s move to
`@RestController`, the meeting model by [`design-decisions-v2.md`](design-decisions-v2.md)'s
propose/vote/confirm rework. Kept below only where the underlying fix is still part of the current
implementation; findings tied to since-replaced mechanisms are summarized, not re-explained in
detail — see those two logs for what the current code actually looks like.

## Spec compliance at the time of review

The implementation already covered the required functionality: the domain model, slot CRUD and
meeting scheduling, slot filtering by `{status, from, to}`, a working `docker-compose up`, and the
two "plus" asks (metrics, a real test suite). Indexing and locking in `MeetingService` showed the
"hundreds of users, thousands of slots" scale note had already been taken seriously in one part of
the codebase — just not applied consistently everywhere (see below).

## Issues found and fixed

### 1. Bean validation annotations were declared but never invoked

`spring-boot-starter-validation` was on the classpath and DTOs carried
`@NotBlank`/`@NotNull`/`@Email`, but the functional routing style in use at the time had no
equivalent of `@Valid` on a `@RequestBody` parameter, so none of it was ever checked — confirmed
live (a blank name and a malformed email both returned `201`; a null `startTime` NPE'd to `500`).
Fixed then with a hand-rolled component that ran the body through the validator manually. That
whole approach — and the functional routing style it existed to patch around — was later deleted
wholesale by [`design-decisions-v6.md`](design-decisions-v6.md), which gets `@Valid @RequestBody`
validation for free from `@RestController`.

### 2. TOCTOU race between the overlap check and the insert/update

`SlotService.create()`/`update()` called `SlotRepository.existsOverlap(...)` and then saved, with
nothing locking the gap between the two — two concurrent requests for overlapping windows on the
same calendar could both pass the check before either committed.

**Fix**: added `CalendarRepository.findByOwnerIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`), acquired at
the top of `SlotService.create()`/`update()` before the overlap check. This serializes slot writes
*per user's calendar* — locking the parent row (not the individual slot rows) is what closes a
phantom-read gap for a brand-new INSERT, which per-row locking on existing rows can't do. This is
still exactly today's locking design (see `conventions.md`).

Verified with a real concurrency test, not just by reading the code — see
[`SlotRouteIT.createSlot_concurrentOverlappingRequests_onlyOneSucceeds`](../../src/test/java/io/irn/minidoodle/web/SlotRouteIT.java):
8 threads race to create the exact same overlapping slot; exactly one gets `201`, the rest get
`409`. Run 5 times in a row with no flakes.

**Trade-off** (flagged, not resolved): this lock is coarse — it serializes *all* slot writes for a
given user against each other, not just genuinely overlapping ones. Acceptable at "hundreds of
users" scale; a finer-grained scheme (e.g. a Postgres exclusion constraint) was considered and
rejected as Postgres-only, since the test suite runs on H2 (see `conventions.md`'s note on
Postgres/H2 divergence for optional-filter queries — the same risk would apply here).

### 3. Meeting responses never exposed participants

Under the original single-slot meeting model, a scheduled meeting's response gave no way to tell
*who* was in it — only opaque slot IDs — even though the brief explicitly lists "participants" as a
meeting detail. Fixing it surfaced a second, unrelated bug worth remembering on its own: an
`@EntityGraph` is a JPA *fetch graph* — any association not listed in it is silently demoted to
`LAZY`, even if its own mapping says `EAGER`. That turned a working query into a
`LazyInitializationException` → 500 the moment a mapper started reading one more association
outside the transaction than the graph declared. Both the participant model and the exact graph
this touched were rebuilt from scratch by
[`design-decisions-v2.md`](design-decisions-v2.md)'s `MeetingParticipant`/vote rework — the
fetch-graph lesson is what survived, and is now the reason `conventions.md` documents each
repository's `@EntityGraph` attribute paths explicitly.

### 4. `SlotService.create()` loaded a user's entire slot collection just to append one row

`CalendarRepository.findByOwnerId` eagerly loaded *every* existing slot for that user, forced by
`calendar.addSlot(slot)` initializing that collection. At "thousands of slots per user" this is
O(n) work and memory on every single slot creation, for an association `Slot` already owns via its
own FK.

**Fix**: added a `Slot(Calendar, Instant, Instant)` constructor that sets the FK directly, so
`SlotService.create()` never touches `Calendar.slots`. `Calendar.addSlot()` remains as-is where the
collection is already needed (e.g. `DataSeeder`). Still exactly today's hot-path pattern (see
`conventions.md`).

## Test coverage audit

Before this pass, all 36 existing tests passed, but none of the four fixes above had any coverage
proving they worked. Added 15 tests (36 → 51) — including
`SlotRouteIT.createSlot_concurrentOverlappingRequests_onlyOneSucceeds` (verified with 5 repeated
runs to rule out flakiness) — bringing every fix above under test.

## Discussed, deliberately not changed

- **No pagination** on list endpoints — flagged as a scale concern given the brief's "hundreds of
  users, thousands of slots" note, but left out of scope for this pass. Addressed properly in
  [`design-decisions-v4.md`](design-decisions-v4.md).
- **Any participant could cancel a meeting for everyone**, since "organizer" wasn't yet a stored
  concept in the original single-slot model. Confirmed as intentional for that pass given the
  "mini Doodle" framing; resolved once [`design-decisions-v2.md`](design-decisions-v2.md) made
  `ORGANIZER` an explicit, checked `ParticipantRole`.
