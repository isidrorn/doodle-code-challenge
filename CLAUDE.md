# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A "mini Doodle" meeting-scheduling API — the submission for a backend take-home challenge.
Spring Boot 4.1, Java 21. `spring.application.name` / Maven artifactId / base package are
`minidoodle` / `io.irn` / `io.irn.minidoodle`. How we work (architecture, conventions, principles)
is written up declaratively in [`conventions.md`](conventions.md) — read it before making changes;
if a change alters a practice it describes, update it in the same commit.

## Build & test commands

**No Maven wrapper is checked into this repo.** Use a local Maven install (or an IDE's bundled
one, e.g. IntelliJ ships one under `<install>/plugins/maven/lib/maven3/bin`).

**Must build with JDK 21, not newer.** Lombok's annotation processing silently fails to run under
newer JDKs (e.g. 26) — `@Getter`, `@Builder`, `@Slf4j`'s `log` field, etc. don't get generated,
which surfaces as a wall of unrelated "cannot find symbol" compile errors across the codebase. Set
`JAVA_HOME` to a JDK 21 before running Maven.

```bash
# Compile only
mvn compile

# Unit + repository tests only — default Surefire include pattern is *Test.java
mvn test

# Everything, including the *IT.java integration tests — no failsafe plugin is configured,
# so *IT classes are NOT picked up by plain `mvn test` unless explicitly selected
mvn test -Dtest=*Test,*IT

# Single test class / single method
mvn test -Dtest=SlotServiceTest
mvn test -Dtest=SlotRouteIT#listSlots_filtersByFreeStatus_returnsMatch

# Run the app
mvn spring-boot:run -Dspring-boot.run.profiles=local   # H2, no Docker
docker-compose up                                       # PostgreSQL
```

App starts on `http://localhost:8080`; `DataSeeder` seeds two users (Alice, Bob) with a few
grid-aligned slots each, in the same time window — check the startup logs for their generated
`userId`s. Swagger UI at `/swagger-ui.html`, Prometheus metrics at `/actuator/prometheus`. See
`api-examples.md` for a full set of `curl` examples, or run `./demo.sh` for a scripted end-to-end
walkthrough against a live instance.

## Architecture

Layering, strictly one-directional — the web layer never touches a repository directly, always
through a service:

```
web/          @RestController classes (UserController, SlotController, MeetingController)
  dto/        request/response records
  mapper/     entity → DTO mapper components
service/      @Service @Transactional — the only layer with business logic
repository/   JpaRepository — no access from web/
domain/       JPA entities — no dependency on any other layer
exception/    HTTP-agnostic domain exceptions thrown by services, mapped to statuses in web/
config/       DataSeeder, OpenApiConfig, TimeGridConfig
```

### Error handling

All errors flow through `GlobalExceptionHandler` (`@RestControllerAdvice`), which **extends
`ResponseEntityExceptionHandler` deliberately** — the class has a catch-all
`@ExceptionHandler(Exception.class)` → 500, and without the parent's inherited, more-specific
handlers, that catch-all would swallow Spring MVC's own exceptions
(`HttpRequestMethodNotSupportedException`, `HandlerMethodValidationException`,
`MissingServletRequestParameterException`, ...) and turn canonical 405s/400s into 500s. The
overrides in the class only sharpen detail messages; don't add a new `@ExceptionHandler` for an
exception type the parent already handles (same-type handlers in one advice are an ambiguity error
at startup) — override the parent's protected method instead. Service-layer code signals errors by
throwing the HTTP-agnostic domain exceptions in `io.irn.minidoodle.exception`
(`NotFoundException` → 404, `ConflictException` → 409, `InvalidInputException` → 400,
`ForbiddenException` → 403); the advice owns the status mapping, and no Spring Web type appears
below the web layer. The advice keeps a `ResponseStatusException` handler purely as a framework
safety net — application code must never throw it.

### Validation

- **Structural validation** lives on the DTO records (`@NotBlank`/`@NotNull`/`@Email`/`@NotEmpty`)
  and is triggered by `@Valid @RequestBody` on the controller method. (`SlotUpdateRequest` is the
  one DTO with no constraints at all — every field optional — so its controller method takes it
  without `@Valid`.)
- **Controller method parameters** carry constraints directly (`@Min(0) int page`,
  `@Max(100) int size`, `@NotEmpty List<Long> userIds`) — Spring's built-in method validation
  raises `HandlerMethodValidationException` → 400, no `@Validated` needed. Out-of-range pagination
  is rejected with 400, never silently clamped.
- **Business-rule validation** (grid alignment, overlap, state-machine legality) lives in the
  services, throwing the domain exceptions above (`InvalidInputException`, `ConflictException`, …).
- Path variables are typed (`@PathVariable Long userId`) — malformed values become
  `MethodArgumentTypeMismatchException` → 400 via `GlobalExceptionHandler`.

### Domain

```
User 1──1 Calendar 1──N Slot N──M Meeting 1──N MeetingParticipant N──1 User
```

- `Calendar` is domain-only — never exposed as a REST resource; slots are addressed through
  `/api/users/{userId}/slots`.
- **Cascade direction matters.** `User.calendar` (`@OneToOne(mappedBy = "owner", cascade = ALL)`)
  cascades down to `Calendar`, which cascades `ALL` to its `Slot`s. `Calendar.owner` (the owning
  side) has no cascade back up. Always persist through `userRepository.save(user)` when the user is
  new — saving from the `Calendar` or `Slot` side does not cascade upward and throws
  `TransientPropertyValueException`.
- **Slots are client-chosen intervals; the time grid only validates boundaries.** `TimeGridConfig`
  (`scheduling.time-grid-minutes`, default 30) requires every client-supplied boundary — slot
  start/end (bulk create and `PATCH`), meeting start/end, availability `from`/`to` — to satisfy
  `epochSecond % (gridMinutes * 60) == 0`, checked in `SlotService` and `MeetingService`. The grid
  never derives stored data (no computed `endTime`), so changing the parameter can't invalidate
  existing rows — a status-only `PATCH` deliberately skips time validation for exactly this
  reason. Meetings book *whole* slots and only slots *fully contained* in the meeting window; a
  participant whose free slot overshoots the window is silently skipped, same rule as v2's
  missing-coverage skip. See design-decisions-v7.md before touching any of this.
- `Slot` carries `@Version` for optimistic locking; a losing concurrent writer gets mapped to 409 by
  `GlobalExceptionHandler`.
- `Slot ─ Meeting` is `@ManyToMany` via the `slot_meeting` join table (owned by `Meeting`). A slot
  can sit in several `PROPOSED` meetings at once but at most one `CONFIRMED` one — not a DB
  constraint, enforced in code by `MeetingService.confirm()` only ever booking slots it already
  confirmed are `FREE`. `Slot.hasConfirmedMeeting()` is what `SlotService.update()`/`delete()` check
  before allowing a mutation (409 if true) — a slot in a merely `PROPOSED` meeting can still be
  freely edited, since nothing has actually reserved it yet.
- A `Meeting` is created `PROPOSED` with zero slots booked (`MeetingService.create()` does no slot
  search/locking at all). Each participant is a `MeetingParticipant` with a `ParticipantRole`
  (`ORGANIZER`/`REQUIRED`/`OPTIONAL`) and a `Vote` (`PENDING`/`YES`/`NO`); the organizer's vote is
  implicitly `YES` from construction. `MeetingService.vote()` drives the state machine: a `REQUIRED`
  participant voting `NO` cancels immediately; once every `REQUIRED` participant has voted `YES`,
  `MeetingService.confirm()` books each participant's `FREE` slots for the meeting window *only if*
  `SlotRepository.findFreeSlotsCovering(...)` returns a full, gap-free chain covering
  `[startTime, endTime)` — a participant without one is skipped silently rather than blocking
  confirmation for everyone else (this resolves an actual contradiction in the brief that produced
  this design; see [`design-decisions-v2.md`](design-decisions-v2.md) for the two conflicting
  requirements and why the silent-skip version won). `Meeting.addSlot()` does the
  book-and-mark-BUSY-and-associate in one call; `Meeting.releaseSlots()` (used by
  `MeetingService.cancel()` when cancelling a `CONFIRMED` meeting) is the inverse.
- `SlotService.create()`/`update()` take a `PESSIMISTIC_WRITE` lock on the *parent* `Calendar` row
  (`CalendarRepository.findByOwnerIdForUpdate`) rather than on individual `Slot` rows — an existing
  row's lock can't close a phantom-read gap for a brand-new `INSERT`, so the overlap check and the
  write are serialized per user's calendar instead. `SlotService.create()` builds each new `Slot` via
  a `Slot(Calendar, Instant, Instant)` constructor that sets the FK directly, rather than going
  through `Calendar.addSlot()` — the latter mutates (and thus forces a full load of)
  `Calendar.slots`, which would mean reloading every existing slot for that user on every creation.
  `Calendar.addSlot()` itself is still fine to use where the collection is already needed (e.g.
  `DataSeeder`, `TestSupport.seedSlot`) — just don't reach for it on a hot, per-request path. Bulk
  slot creation (`SlotBulkCreateRequest.slots`) is transactional all-or-nothing: any invalid or
  conflicting entry throws inside the single `@Transactional` service method, so the whole batch
  rolls back — no partial-success/207 handling needed.
- `spring.jpa.open-in-view: false` is set deliberately. Any repository method whose result gets
  read after its `@Transactional` service method returns (e.g. a mapper touching a lazy
  `@ManyToMany`/`@OneToMany`) must eagerly fetch what it needs — see
  `SlotRepository.findByIdInWithMeetings`/`findByUserIdAndSlotId`
  (`@EntityGraph(attributePaths = "meetings")`, since `SlotMapper` reads `slot.getMeetings()`) and
  `MeetingRepository.findById` (`@EntityGraph(attributePaths = {"participants",
  "participants.user"})`, since `MeetingMapper` reads `participant.getUser().getName()`). Don't
  "fix" a `LazyInitializationException` by re-enabling open-in-view.
- **`@EntityGraph(attributePaths = ...)` is a JPA *fetch graph*, not just an eager-fetch hint: any
  association NOT listed in it gets demoted to `LAZY`, even if its own mapping is `EAGER` by
  default.** This is why `MeetingRepository.findById()`'s graph is `{"participants",
  "participants.user"}` and *not* `"slots"` — `MeetingMapper` doesn't read `slots` at all
  (`MeetingResponse` doesn't expose them). `MeetingService.confirm()`/`cancel()` still mutate
  `meeting.getSlots()`, but always from inside the active transaction, where lazy loading works
  regardless of what's in the graph — the graph only matters for what gets read *after* the
  transaction ends.
- **A JPQL bind parameter used only in a bare `(:param is null or ...)` check breaks on real
  Postgres, but not on H2.** Postgres's extended query protocol resolves a parameter's type at
  parse time from its surrounding SQL; a standalone `? IS NULL` with no comparison/cast gives it
  nothing to infer from, and it fails with `could not determine data type of parameter $n`. H2
  doesn't enforce this, so the whole test suite (H2-backed) passes while the exact same query 500s
  against `docker-compose`'s Postgres. Fix is an explicit `cast(:param as <type>)` in the null-check
  branch — see `SlotRepository.searchIds` and `existsOverlap` for the pattern. Any new
  optional-filter query with this shape needs the same treatment, and should be smoke-tested against
  `docker-compose up`, not just `mvn test`. (`findFreeSlotsCovering` has no optional/nullable
  parameters, so it isn't at risk of this — but it was still smoke-tested against Postgres directly,
  see `design-decisions-v2.md`.)

### API routes

```
GET    /api/users                                             list users (paginated)
GET    /api/users/{userId}                                    get user
POST   /api/users                                              create user (also creates their Calendar)

GET    /api/users/{userId}/slots                              list slots (optional status/from/to filters; paginated)
POST   /api/users/{userId}/slots                              bulk-create slots (slots[{startTime,endTime}] in body)
GET    /api/users/{userId}/slots/{slotId}                     get slot
PATCH  /api/users/{userId}/slots/{slotId}                     update slot (startTime, status)
DELETE /api/users/{userId}/slots/{slotId}                     delete slot

POST   /api/meetings                                          propose a meeting (PROPOSED)
GET    /api/meetings/{meetingId}                               get meeting
POST   /api/meetings/{meetingId}/cancel                        cancel meeting (organizer only; body: {userId})
POST   /api/meetings/{meetingId}/participants/{userId}/vote   cast a vote (body: {vote})
GET    /api/meetings/availability                              find free windows across users (userIds, from, to params)
```

The slot-list filter params are all optional (absent = "no filter on that dimension"); the
availability params are all required — there's no sensible default for an availability search.
`GET /api/meetings/availability` coexists with `GET /api/meetings/{meetingId}` because Spring's
path matching always prefers the literal segment over the template. See design-decisions-v5.md.

### Pagination

Every list endpoint (`UserController.listAll`, `SlotController.list`) is paginated via
`page`/`size` query params validated with `@Min`/`@Max` constraints — defaults `page=0`/`size=20`,
size capped at 100 (400 if out of range, not silently clamped) — and returns
[`PageResponse<T>`](src/main/java/io/irn/minidoodle/web/dto/PageResponse.java) instead of a bare
array.

**Slot search is `searchIds()` + `findByIdInWithMeetings()`, deliberately two queries.**
Pagination (LIMIT/OFFSET) and a `@ManyToMany` fetch join (`@EntityGraph(attributePaths =
"meetings")`, needed because `SlotMapper` reads `slot.getMeetings()` with `open-in-view: false`)
don't combine safely: with a collection fetch-joined in the same query, Hibernate can't apply
LIMIT/OFFSET in SQL (a fetch join can multiply rows), so it silently paginates the *entire* result
set in memory instead — exactly defeating the point of paginating a "thousands of slots" endpoint.
`searchIds()` selects just the page's ids with real SQL-level pagination (no fetch join, so it's
safe); `findByIdInWithMeetings()` loads those specific entities with `meetings` eagerly fetched
(safe here — no LIMIT/OFFSET on this query, the id list already fixes which rows come back).
`SlotService.query()` composes the two and wraps the result in a `PageImpl` using the first query's
total count. Any new paginated query needing an eager collection needs the same two-step shape —
don't add `@EntityGraph` directly to a `Pageable`-accepting query.

### Tests

| Layer | Classes | Notes |
|---|---|---|
| Unit (Mockito, no Spring context) | `SlotServiceTest`, `MeetingServiceTest` | |
| Repository (`@DataJpaTest`) | `SlotRepositoryTest`, `CalendarRepositoryTest` | |
| Integration (`@SpringBootTest`, `RANDOM_PORT`, H2) | `UserRouteIT`, `SlotRouteIT`, `MeetingRouteIT` | real socket via `TestRestTemplate`, not MockMvc |

`SlotRouteIT.createSlots_concurrentOverlappingRequests_onlyOneSucceeds` is a real concurrency test
(8 threads racing over real HTTP, synchronized with a `CountDownLatch`) proving the
`PESSIMISTIC_WRITE` locking in `SlotService` actually serializes writers — not a mocked unit test.
If you touch the locking in `SlotService.create()`/`update()`, run this one specifically
(`mvn test -Dtest=SlotRouteIT#createSlots_concurrentOverlappingRequests_onlyOneSucceeds`), ideally a
few times in a row, since a broken lock would only show up as occasional extra `201`s.

- No shared base class for IT tests — each `*RouteIT` carries its own
  `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureTestRestTemplate` +
  `@ActiveProfiles("test")`, and calls `TestSupport.cleanUp(...)` in `@BeforeEach`. Spring Boot 4
  requires `@AutoConfigureTestRestTemplate` explicitly (no longer auto-configured), and
  `TestRestTemplate` moved to `org.springframework.boot.resttestclient`. `spring-boot-restclient`
  is an explicit test dependency in `pom.xml` because `spring-boot-resttestclient` does not pull it
  in transitively, and `TestRestTemplateTestAutoConfiguration` needs `RestTemplateBuilder` on the
  classpath to even evaluate its conditions.
- Seed/cleanup helpers live in `TestSupport` (static methods, not a base class). When seeding a
  `Slot` for a test, save it via `SlotRepository.save(slot)` — not `CalendarRepository.save(cal)`.
  The `Calendar` fetched by `findByOwnerId` already has an id, so `JpaRepository.save` on it calls
  `merge()`, returning a detached copy and leaving the `Slot` you're holding with a `null` id.
- `@MockBean`/`@SpyBean` are gone in Spring Boot 4 — use plain `@Mock` +
  `@ExtendWith(MockitoExtension.class)` for unit tests.
- `DataSeeder` is excluded under the `test` profile (`@Profile("!test")`).
- 127 tests total as of the review-hardening pass (design-decisions-v8.md). CI (GitHub Actions,
  `.github/workflows/ci.yml`) runs the full `*Test,*IT` pattern on every push — keep that pattern
  in sync if test naming conventions ever change.

### Schema management: Flyway (docker-compose/Postgres only) vs. Hibernate auto-DDL (local/test)

`application.yml` (default profile, used by `docker-compose`) sets `hibernate.ddl-auto: validate`
and `spring.flyway.enabled: true` — migrations in `src/main/resources/db/migration` own the schema;
Hibernate only checks its entity mappings match. `application-local.yml` and
`application-test.yml` both set `spring.flyway.enabled: false` and keep `ddl-auto: create-drop` —
the migrations are Postgres SQL and aren't run against H2. If you add or change a JPA mapping that
affects the schema, add a new `V{n}__description.sql` migration for the Postgres profile — `mvn
test`/H2 won't catch a mismatch, only `docker-compose up` (Hibernate's `validate` fails fast at
startup if the migration and the entity mapping disagree). See design-decisions-v4.md for why
`flyway-core` alone isn't enough to make Flyway's autoconfiguration activate on Spring Boot 4 — you
need `spring-boot-starter-flyway` too.

## Docs: conventions vs. decision logs

- [`conventions.md`](conventions.md) — normative "how we do things." Keep it in sync with reality:
  a change that alters a practice it describes updates it in the same commit.
- [`README.md`](README.md) — written for a technical reviewer evaluating this as a take-home
  submission: domain, API, run/consume/test instructions, known limitations.
- [`requirements-mapping.md`](requirements-mapping.md) — traces each requirement of the brief to
  its implementation, decisions, and trade-offs. Like conventions.md it describes the *current*
  state: update it in the same commit as any change that alters requirement-visible behavior.
- Six files are **historical records**, not current TODO lists — don't re-fix anything they
  describe as already fixed, and don't merge them; they document six separate passes:
  - [`spec-review.md`](spec-review.md) (v1): spec-compliance pass on the original single-slot
    meeting model — dead bean validation, the TOCTOU race and the calendar-level lock that fixed
    it, an O(n) collection load on every slot create.
  - [`design-decisions-v2.md`](design-decisions-v2.md): the propose/vote/confirm meeting-model
    refactor — read before touching `MeetingService.vote()`/`confirm()`; it documents a real
    contradiction in the brief and which behavior won.
  - [`design-decisions-v3.md`](design-decisions-v3.md): input-validation hardening — bad-typed path
    variables and malformed bodies 400 (with specific messages) instead of 500.
  - [`design-decisions-v4.md`](design-decisions-v4.md): pagination (the two-query shape above) and
    Flyway for the Postgres profile.
  - [`design-decisions-v5.md`](design-decisions-v5.md): the cross-participant availability search —
    read before touching `MeetingService.availability()`; it explains the per-user reuse of
    `findFreeSlotsCovering` and why grid-aligned fixed-duration slots reduce it to set membership.
  - [`design-decisions-v6.md`](design-decisions-v6.md): the conversion from functional routes to
    `@RestController` — which hand-rolled mechanisms were deleted and what replaced each, plus the
    `ResponseEntityExceptionHandler` wiring rationale. The v1–v5 logs reference the pre-v6 web
    machinery (`RequestValidator`, `RouterExceptionFilter`, handlers) in their historical context;
    that's intentional, don't "fix" them.
  - [`design-decisions-v7.md`](design-decisions-v7.md): per-slot durations on a validation-only
    time grid — reverses v2's fixed-duration model; read before touching `TimeGridConfig`,
    slot creation/PATCH validation, or `MeetingService.availability()`. (v2–v5 describe the
    fixed-duration era in their historical context; same rule, don't "fix" them.)
  - [`design-decisions-v8.md`](design-decisions-v8.md): the review-hardening pass — domain
    exceptions, the POST /cancel rationale, length caps + unique email (read its migration
    lesson before writing any migration that adds a constraint over existing data), CI.
    (v1–v7 mention `ResponseStatusException`-throwing services and the DELETE cancel route in
    their historical context; same rule applies.)
