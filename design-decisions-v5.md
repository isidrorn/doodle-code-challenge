# Design decisions: cross-participant availability

A single addition — companion to
[`spec-review.md`](spec-review.md), [`design-decisions-v2.md`](design-decisions-v2.md),
[`design-decisions-v3.md`](design-decisions-v3.md), and [`design-decisions-v4.md`](design-decisions-v4.md).
Don't merge them, separate passes.

## The gap this closes

Every other piece of "mini Doodle" was covered: slots, meetings, propose/vote/confirm, `QUERY`-based
filtering. What wasn't there was the one thing real Doodle is actually known for — suggesting a time
that works, instead of requiring the organizer to already know one. Proposing a meeting
(`POST /api/meetings`) always required a specific `startTime`/`endTime` chosen in advance; nothing
answered "when, across these N people, is everyone (or anyone) actually free?"

## What was added

`QUERY /api/meetings/availability` — body: `{userIds, from, to}`. For every slot-grid window in
`[from, to)`, returns which of the requested users have a `FREE` slot there:

```json
[
  {"startTime": "2026-06-01T09:00:00Z", "endTime": "2026-06-01T09:30:00Z", "freeUserIds": [1, 2]},
  {"startTime": "2026-06-01T09:30:00Z", "endTime": "2026-06-01T10:00:00Z", "freeUserIds": [1]}
]
```

Deliberately returns *who's* free per window rather than collapsing to "only windows where everyone
is free" — a caller who cares about strict full-availability filters client-side on
`freeUserIds.size() == userIds.size()`; the endpoint doesn't need to know which participants are
required vs. optional to be useful, and baking that distinction in server-side would have coupled
this to the meeting-proposal flow instead of being a general "what does availability look like"
query.

## Implementation: no new query, reuse of an existing one

`SlotRepository.findFreeSlotsCovering(userId, from, to)` already answers "give me this user's FREE
slots in this range" — it's what `MeetingService.confirm()` calls per participant to check coverage
for one meeting window. `MeetingService.availability()` calls it once per requested user, collects
each user's free slot start-times into a set, then walks `[from, to)` in slot-grid increments
(`slotDurationMinutes`) checking which users' sets contain each window's start. Grid-aligned slots
of a fixed system-wide duration (see `SlotDurationConfig`) make this a plain set-membership check
per window rather than general interval-intersection math — a simplification specific to this
domain's existing constraint, not something that would hold if slot duration were ever made
per-slot again.

## Validation choices

- **`from` must be grid-aligned, and `to - from` must be an exact multiple of the slot duration** —
  same shape as the checks `SlotService`/`MeetingService` already apply elsewhere
  (`epochSecond % (slotDurationMinutes * 60) == 0`). Necessary here specifically because the
  implementation walks the grid starting from `from`; a misaligned `from` would generate windows
  that can never match any real slot's start time, silently returning nothing instead of erroring.
- **The requested range is capped at `MeetingService.MAX_AVAILABILITY_WINDOWS` (2,000 windows)** —
  the loop is `O((to - from) / slotDuration)`, so an unbounded caller-supplied range would let one
  request drive an arbitrarily large amount of server-side work. Same reasoning, same shape, as
  `RequestValidator`'s `MAX_PAGE_SIZE` (design-decisions-v4.md): reject out-of-range with a clear
  400 rather than silently truncating the response.
- **Every field in `AvailabilityQuery` is required**, and the handler goes through the normal
  `RequestValidator.parseAndValidate` path — unlike `SlotHandler.parseFilter`'s "empty body means no
  filter" convention for `QUERY /api/users/{userId}/slots`. That convention exists because an absent
  filter has an obvious, useful default (list everything); there's no equivalent default for "find
  availability" — an empty body can't mean anything except a client mistake, so it 400s like any
  other malformed request body.

Verified live before writing tests (both the happy path and every validation error), then covered:
`MeetingServiceTest` (grid alignment, range-multiple check, the window cap, user-not-found, the
core intersection logic, deduping a repeated `userId`) and `MeetingRouteIT` (the full HTTP path,
including a case where the queried user has no free slots at all — 200 with an empty array, not an
error). Full suite: 123/123.
