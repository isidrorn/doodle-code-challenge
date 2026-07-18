# API examples — cURL reference

A per-resource, copy-pasteable reference of every route. For a scripted, runnable walkthrough of
the whole flow (create users, filter slots, propose/vote/confirm/cancel a meeting)
against a live instance, see [`demo.sh`](demo.sh) instead — this file is for browsing and
copy-pasting one request at a time.

Start the app first (no Maven wrapper is checked into this repo — use a local Maven install with a
**JDK 21** toolchain; see the [README](README.md#run) for why):
```bash
# With docker-compose (PostgreSQL)
docker-compose up

# Or local H2 (no Docker required)
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

> The seeder creates two users (Alice and Bob), each with grid-aligned slots in the same time
> window. Check the logs for their IDs: `Seeded user='Alice' userId=1 calendarId=1`

```bash
BASE="http://localhost:8080"
```

Dates below are placeholders — swap them for something in the future relative to when you run
this, or just run [`demo.sh`](demo.sh), which computes them dynamically. They're also shown aligned
to the default 30-minute time grid (`scheduling.time-grid-minutes`) — every client-supplied
boundary must satisfy `epochSecond % (gridMinutes * 60) == 0` or the request is rejected with 400.

---

## Input validation

Applies across every route in this API, not just one resource — every `{userId}`/`{slotId}`/
`{meetingId}` path segment and every request body is validated before it reaches business logic:

```bash
# A path id that isn't a number → 400 with a specific message, not a 500
curl -s -w "\n%{http_code}\n" "$BASE/api/users/not-a-number"

# Same fix applies to every id-shaped path variable, everywhere it appears
curl -s -w "\n%{http_code}\n" "$BASE/api/users/1/slots/not-a-number"
curl -s -w "\n%{http_code}\n" "$BASE/api/meetings/not-a-number"

# A body that isn't valid JSON at all → 400 with the parser's own error, not a 500
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/users" \
  -H "Content-Type: application/json" \
  -d '{not valid json'

# A body field with a value that doesn't match its declared type (here, an enum) → 400
curl -s -w "\n%{http_code}\n" -X PATCH "$BASE/api/users/1/slots/1" \
  -H "Content-Type: application/json" \
  -d '{"status":"NOT_A_STATUS"}'

# A syntactically valid but nonexistent id is a 404, not a 400 — type validation only rejects
# things that aren't numbers at all, not numbers that don't happen to match a row
curl -s -w "\n%{http_code}\n" "$BASE/api/users/999999"
```

---

## Pagination

Every list endpoint (`GET /api/users`, `GET /api/users/{userId}/slots`) is paginated
via ordinary `page`/`size` query params — defaults `page=0`, `size=20`, capped at `size=100` — and
returns an envelope instead of a bare array: `{content, page, size, totalElements, totalPages}`.

```bash
curl -s "$BASE/api/users?page=0&size=1" | jq

# size out of [1, 100] → 400, not silently clamped
curl -s -w "\n%{http_code}\n" "$BASE/api/users?size=1000"
```

---

## Users

```bash
# List all users (paginated — see "Pagination" above)
curl -s "$BASE/api/users" | jq

# Get one user
curl -s "$BASE/api/users/1" | jq

# Create a user (also creates their calendar automatically)
curl -s -X POST "$BASE/api/users" \
  -H "Content-Type: application/json" \
  -d '{"name":"Carol","email":"carol@example.com"}' | jq

# Invalid input → 400 with a ProblemDetail body
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/users" \
  -H "Content-Type: application/json" \
  -d '{"name":"","email":"not-an-email"}'
```

---

## Slots

Each slot is a client-chosen `[startTime, endTime)` — durations are up to the user, one slot can
span 30 minutes or three hours. The time grid (`scheduling.time-grid-minutes`, default 30) only
validates that both boundaries sit on grid multiples; see `design-decisions-v7.md`.

```bash
USER=1   # replace with the actual userId from the seeder log

# List all slots (no filter; paginated, see "Pagination" above)
curl -s "$BASE/api/users/$USER/slots" | jq

# Filter by status — every filter param is optional; absent means "no filter on that dimension"
curl -s "$BASE/api/users/$USER/slots?status=FREE" | jq

# Filter by time range
curl -s "$BASE/api/users/$USER/slots?from=2027-01-01T08:00:00Z&to=2027-01-01T11:00:00Z" | jq

# Combined filter
curl -s "$BASE/api/users/$USER/slots?status=FREE&from=2027-01-01T08:00:00Z&to=2027-01-01T12:00:00Z" | jq

# A malformed filter value → 400, never silently ignored
curl -s -w "\n%{http_code}\n" "$BASE/api/users/$USER/slots?from=not-a-date"

# Get single slot
curl -s "$BASE/api/users/$USER/slots/1" | jq

# Bulk-create slots, each with its own duration — all are created or none are, on any failure
# (see design-decisions-v2.md on why this is transactional all-or-nothing)
curl -s -X POST "$BASE/api/users/$USER/slots" \
  -H "Content-Type: application/json" \
  -d '{"slots":[{"startTime":"2027-01-02T09:00:00Z","endTime":"2027-01-02T09:30:00Z"},
                {"startTime":"2027-01-02T10:00:00Z","endTime":"2027-01-02T12:00:00Z"}]}' | jq

# A boundary off the grid → 400
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/users/$USER/slots" \
  -H "Content-Type: application/json" \
  -d '{"slots":[{"startTime":"2027-01-02T09:07:00Z","endTime":"2027-01-02T09:30:00Z"}]}'

# An interval overlapping an existing slot → 409 (whole batch fails, nothing is created)
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/users/$USER/slots" \
  -H "Content-Type: application/json" \
  -d '{"slots":[{"startTime":"2027-01-02T09:00:00Z","endTime":"2027-01-02T09:30:00Z"}]}'

# Mark slot as BUSY
curl -s -X PATCH "$BASE/api/users/$USER/slots/1" \
  -H "Content-Type: application/json" \
  -d '{"status":"BUSY"}' | jq

# Reschedule — startTime alone shifts the slot preserving its length;
# endTime alone resizes it in place; sending both defines a new interval
curl -s -X PATCH "$BASE/api/users/$USER/slots/1" \
  -H "Content-Type: application/json" \
  -d '{"startTime":"2027-01-02T10:00:00Z"}' | jq

# Delete a slot
curl -s -X DELETE "$BASE/api/users/$USER/slots/3" -o /dev/null -w "%{http_code}\n"
```

A slot booked into a `CONFIRMED` meeting cannot be modified or deleted (409) — a slot only in
`PROPOSED` meetings can be, since nothing has actually been reserved for those yet.

---

## Meetings

A meeting starts `PROPOSED` with no slots booked. It only books slots — and only for participants
who have a full, free cover of the meeting's window — once every `REQUIRED` participant votes YES.

```bash
ALICE=1
BOB=2
CAROL=3

# Propose a meeting: Alice organizes, Bob is required, Carol is optional.
# The organizer's vote is implicitly YES from creation.
curl -s -X POST "$BASE/api/meetings" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"Team sync\",\"description\":\"Weekly\",\"organizerUserId\":$ALICE,\"startTime\":\"2027-01-02T09:00:00Z\",\"endTime\":\"2027-01-02T10:00:00Z\",\"requiredParticipantUserIds\":[$BOB],\"optionalParticipantUserIds\":[$CAROL]}" | jq

# Invalid input (blank title) → 400
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/meetings" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"\",\"organizerUserId\":$ALICE,\"startTime\":\"2027-01-02T09:00:00Z\",\"endTime\":\"2027-01-02T10:00:00Z\"}"

# Get meeting by id — participants carry userId, name, role, and current vote
curl -s "$BASE/api/meetings/1" | jq

# Bob (REQUIRED) votes YES — if he's the last REQUIRED participant to vote YES, the
# meeting confirms and books FREE slots covering the window for every participant that has them
curl -s -X POST "$BASE/api/meetings/1/participants/$BOB/vote" \
  -H "Content-Type: application/json" \
  -d '{"vote":"YES"}' | jq

# A REQUIRED participant voting NO cancels the meeting immediately
curl -s -X POST "$BASE/api/meetings/1/participants/$BOB/vote" \
  -H "Content-Type: application/json" \
  -d '{"vote":"NO"}' | jq

# Voting on a meeting that isn't PROPOSED anymore → 409
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/meetings/1/participants/$BOB/vote" \
  -H "Content-Type: application/json" \
  -d '{"vote":"YES"}'

# Cancel a meeting — a POST action, not DELETE: nothing is deleted (the meeting transitions to
# CANCELLED and stays retrievable), and RFC 9110 gives a DELETE body no semantics anyway.
# Only the organizer can cancel; the body identifies the caller. If the meeting was CONFIRMED,
# every booked slot goes back to FREE.
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/meetings/1/cancel" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":$ALICE}"

# Non-organizer tries to cancel → 403
curl -s -w "\n%{http_code}\n" -X POST "$BASE/api/meetings/1/cancel" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":$BOB}"

# ── Availability search — find a time that works, instead of already knowing one ──────────────
# Every parameter is required here (unlike the slot-list filters) — there's no sensible default
# for "find availability." Returns one entry per slot-grid window in [from,to) where at least one
# of userIds is FREE; freeUserIds is the subset actually free at that window, not necessarily all
# of them. See design-decisions-v5.md.
curl -s "$BASE/api/meetings/availability?userIds=$ALICE,$BOB&from=2027-01-02T09:00:00Z&to=2027-01-02T10:00:00Z" | jq

# from not aligned to the time grid → 400
curl -s -w "\n%{http_code}\n" "$BASE/api/meetings/availability?userIds=$ALICE&from=2027-01-02T09:00:07Z&to=2027-01-02T10:00:00Z"
# ──────────────────────────────────────────────────────────────────────────────────────────────
```

---

## Observability

```bash
# Health
curl -s "$BASE/actuator/health" | jq

# Prometheus metrics
curl -s "$BASE/actuator/prometheus" | grep http_server_requests

# Swagger UI
open "$BASE/swagger-ui.html"
```
