# Design decisions: per-slot durations on a validation-only time grid (v7)

Companion to [`spec-review.md`](spec-review.md) and `design-decisions-v2..v6.md` — separate
passes, don't merge them. This one revisits how the brief's *"create available time slots with
configurable duration"* is modeled, and reverses a v2-era decision.

## The problem with the previous model

From the v2 refactor until this pass, slot duration was a **system-wide constant**
(`scheduling.slot-duration-minutes`): every slot was exactly that long, `endTime` was *derived* —
computed server-side as `startTime + duration`, never accepted from a client. That reading treated
"configurable" as "a configuration parameter rather than a hardcoded constant." Two things were
wrong with it:

1. **It's not what the requirement most naturally says.** "Create time slots with configurable
   duration" reads as: the *user* decides when a slot starts and when it ends, and that whole time
   frame is *a* slot — not "the user is assigned uniform slots of an operator-chosen size."
2. **The parameter could break the application.** Because the config value *derived domain data*,
   changing it on a system with existing rows was unsafe: slots created on a 30-minute grid stop
   making sense under a 45-minute one — coverage checks and availability walks silently
   misbehave. A configuration parameter whose change can invalidate stored data is poor design,
   full stop; this was flagged as an undocumented operational caveat and fixed rather than
   documented around.

## The new model: the grid validates boundaries, it never derives data

- A slot is a client-chosen interval: `POST /api/users/{userId}/slots` takes
  `{"slots": [{"startTime": ..., "endTime": ...}]}`. One slot can be 30 minutes or three hours.
- `scheduling.time-grid-minutes` (default 30, `TimeGridConfig`) is a **granularity rule for new
  writes only**: every client-supplied boundary — slot start/end, meeting start/end, availability
  `from`/`to` — must be a multiple of it (`epochSecond % (grid * 60) == 0`). With a 5-minute grid,
  `09:12` is rejected and `09:05`/`09:55` are fine.
- **Changing the parameter is now safe by construction.** Stored rows are never re-derived or
  re-judged: loosening the grid (30 → 5) keeps every stored boundary valid (multiples of 30 are
  multiples of 5); tightening it (5 → 30) only rejects off-grid boundaries on *future* writes.
  A deliberately supporting detail: a **status-only PATCH performs no time validation**, so a slot
  created under a finer grid stays freely markable busy/free even after the grid was tightened —
  only moving/resizing it must land on the current grid.

Why keep a grid at all, instead of accepting arbitrary instants? Bounded granularity keeps
boundaries meaningful (no `09:12:47` slots), makes the availability walk finite and aligned, and
matches how scheduling products actually quantize time. The grid is the *alignment* rule;
duration is the user's choice — the two concerns were conflated before and are now separate.

## What survived unchanged — and why that's not luck

- **`SlotRepository.existsOverlap`** was always a true interval-overlap predicate
  (`a < d && b > c`), never a same-start check — variable-length slots need nothing new for
  double-booking prevention, and the calendar-level `PESSIMISTIC_WRITE` lock closes the same
  phantom gap it always did.
- **`MeetingService.confirm()` / `fullyCovers()`** walks a gap-free chain of FREE slots
  (`cursor == slot.start → cursor = slot.end`), which never assumed equal lengths: a 60-minute
  meeting window is covered equally well by two 30-minute slots or one 60-minute slot.
- **Booking policy** (unchanged, now worth stating explicitly): a meeting books *whole* slots, and
  only slots **fully contained** in the meeting window (`findFreeSlotsCovering`). A participant
  whose only FREE slot extends beyond the window (e.g. a 09:00–11:00 slot for a 09:00–10:00
  meeting) is *skipped* under the same silent-skip rule as v2 — booking would consume more of
  their calendar than the meeting occupies, and splitting slots is deliberately out of scope.

## What had to change: availability

The old availability walk collected each user's free-slot *start times* into a set and did
per-window set membership — correct only while every slot was exactly one window wide. With
client-chosen durations, one long FREE slot covers many windows (and may extend beyond the queried
range), so:

- New query `SlotRepository.findFreeSlotsOverlapping(userId, from, to)` — *overlapping*, unlike
  `findFreeSlotsCovering`'s contained-only semantics (a slot starting before `from` still counts).
  No nullable parameters, so the H2-vs-Postgres `cast()` gotcha doesn't apply; still smoke-tested
  against the real docker-compose Postgres rather than assumed.
- A user is free for a grid window iff one of their FREE slots **contains** it. A window can never
  straddle two slots: slot boundaries sit on the grid and a window is exactly one grid step wide,
  so containment against single slots is sufficient — no interval-union math needed.

## PATCH semantics with two time fields

`SlotUpdateRequest` is now `{startTime?, endTime?, status?}`:

- `startTime` alone **shifts** the slot, preserving its current length (matches the old
  behavior's spirit, where a reschedule kept the — then fixed — duration).
- `endTime` alone **resizes** in place.
- Both together define a completely new interval.
- Any supplied boundary is grid-validated and overlap-checked under the calendar lock;
  status-only PATCHes skip all of that (see above).

## No schema change

`slot.start_time`/`end_time` were always stored columns — deriving `endTime` was service-layer
behavior, not schema. No new Flyway migration is needed; existing data is valid as-is under the
new rules.

## Verified

123 tests green (unit cases for both-boundary validation, in-request overlap conflicts, variable
durations, shift-vs-resize PATCH; repository test proving overlapping-vs-covering semantics; IT
cases over real HTTP including a 90-minute slot). The 8-thread concurrency IT passed three
consecutive runs. The full flow — variable-length slot creation, filtering, availability with a
long spanning slot, propose → vote → confirm → cancel — was exercised live against the
docker-compose Postgres stack.
