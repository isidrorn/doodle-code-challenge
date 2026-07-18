# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Two things in one repo: a playground for the HTTP `QUERY` method
(`draft-ietf-httpbis-safe-method-w-body`) and a "mini Doodle" meeting-scheduling API that gives
`QUERY` something real to filter. Spring Boot 4.1, Java 21. `spring.application.name` /
Maven artifactId / base package are all `minidoodle` / `io.irn` / `io.irn.minidoodle` — the app
identity is the scheduling platform; the `QUERY` demo is the reason it's built the way it is.

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
mvn test -Dtest=SlotRouteIT#querySlots_filtersByFreeStatus_returnsMatch

# Run the app
mvn spring-boot:run -Dspring-boot.run.profiles=local   # H2, no Docker
docker-compose up                                       # PostgreSQL
```

App starts on `http://localhost:8080`; `DataSeeder` seeds two users (Alice, Bob) with a few
grid-aligned slots each, in the same time window — check the startup logs for their generated
`userId`s. Swagger UI at `/swagger-ui.html`,
Prometheus metrics at `/actuator/prometheus`. See `api-examples.md` for a full set of `curl`
examples, or run `./demo.sh` for a scripted end-to-end walkthrough against a live instance.

## Architecture

Layering, strictly one-directional — the web layer never touches a repository directly, always
through a service:

```
web/          HandlerFunction + RouterFunction (WebMvc.fn) — no @RestController for the domain API
  dto/        request/response records
  mapper/     entity → DTO mapper components
service/      @Service @Transactional — the only layer with business logic
repository/   JpaRepository — no access from web/
domain/       JPA entities — no dependency on any other layer
config/       DataSeeder, OpenApiConfig
```

`GlobalExceptionHandler` (`@RestControllerAdvice`) exists only as a fallback for any future
`@RestController`; it does not fire for the domain API described below.

### Why functional routes instead of `@RestController`

`HttpMethod.valueOf("QUERY")` is an open value object in Spring 6/7, so it dispatches without
touching `RequestMappingHandlerMapping`. `@RequestMapping`, however, is closed to the `RequestMethod`
enum and can't express `QUERY` at all. Every route is therefore declared as a `RouterFunction` +
`HandlerFunction` in `SlotRouterConfig`, not as annotated controller methods:

```java
route()
    .GET(SLOTS,  accept(APPLICATION_JSON), slots::listAll)
    .route(method(QUERY).and(path(SLOTS)).and(accept(APPLICATION_JSON)), slots::query)
    .POST(SLOTS, contentType(APPLICATION_JSON), slots::create)
    .build()
    .filter(routerExceptionFilter::filter);
```

Consequence: `@RestControllerAdvice` does **not** intercept exceptions from a `HandlerFunction` (it
only targets `HandlerMethod`/`@Controller`). Error handling instead goes through
`RouterExceptionFilter`, a `HandlerFilterFunction` chained onto the `RouterFunction`, mapping
`ResponseStatusException` → `ProblemDetail`, `ObjectOptimisticLockingFailureException` → 409, and
anything else → 500.

Gotcha specific to `QUERY`: some HTTP clients (including `TestRestTemplate` used in this project's
own tests) don't set a `Content-Length` header for a body sent with a non-standard method. Never
gate body parsing on `Content-Length` being present — attempt the parse and treat a genuinely
empty/absent body as "no filter" via the parse failure itself (see `SlotHandler.parseFilter`).

### Validation

Functional routes have no equivalent of `@Valid` on a `@RequestBody` parameter —
`ServerRequest.body(Class)` never invokes a `Validator`, so the `@NotBlank`/`@NotNull`/`@Email`/
`@NotEmpty` annotations on `UserCreateRequest`, `SlotBulkCreateRequest`, `MeetingCreateRequest`,
`VoteRequest`, and `MeetingCancelRequest` would silently never be checked, even with
`spring-boot-starter-validation` on the classpath. Every handler that parses one of those DTOs must
go through
[`RequestValidator`](src/main/java/io/irn/minidoodle/web/RequestValidator.java)
(`requestValidator.parseAndValidate(request, X.class)`) instead of calling `request.body(X.class)`
directly — it runs the autoconfigured `jakarta.validation.Validator` explicitly and throws
`ResponseStatusException(BAD_REQUEST, ...)` on violations. A new validated DTO/handler that skips
this and calls `request.body(...)` directly will compile fine and silently accept invalid input.
(`SlotUpdateRequest` is the one DTO with no bean-validation constraints at all — every field is
optional — so its handler calls `request.body(...)` directly; grid-alignment/overlap checks happen
as ordinary business-rule validation inside `SlotService` instead.)

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
- **Slot duration is a system parameter** (`SlotDurationConfig`, `scheduling.slot-duration-minutes`,
  default 30), not chosen per-slot. `endTime` is always computed as `startTime + slotDurationMinutes`
  — never accepted from a client — and every `startTime` (bulk slot creation, `PATCH` reschedule,
  and meeting creation) must satisfy `epochSecond % (slotDurationMinutes * 60) == 0`, checked in
  `SlotService` and `MeetingService`.
- `Slot` carries `@Version` for optimistic locking; a losing concurrent writer gets mapped to 409 by
  `RouterExceptionFilter`.
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
  slot creation (`SlotBulkCreateRequest.startTimes`) is transactional all-or-nothing: any invalid or
  conflicting entry throws inside the single `@Transactional` service method, so the whole batch
  rolls back — no partial-success/207 handling needed.
- `spring.jpa.open-in-view: false` is set deliberately. Any repository method whose result gets
  read after its `@Transactional` service method returns (e.g. a mapper touching a lazy
  `@ManyToMany`/`@OneToMany`) must eagerly fetch what it needs — see `SlotRepository.search`/
  `findByUserIdAndSlotId` (`@EntityGraph(attributePaths = "meetings")`, since `SlotMapper` reads
  `slot.getMeetings()`) and `MeetingRepository.findById`
  (`@EntityGraph(attributePaths = {"participants", "participants.user"})`, since `MeetingMapper`
  reads `participant.getUser().getName()`). Don't "fix" a `LazyInitializationException` by
  re-enabling open-in-view.
- **`@EntityGraph(attributePaths = ...)` is a JPA *fetch graph*, not just an eager-fetch hint: any
  association NOT listed in it gets demoted to `LAZY`, even if its own mapping is `EAGER` by
  default.** This is why `MeetingRepository.findById()`'s graph is `{"participants",
  "participants.user"}` and *not* `"slots"` — `MeetingMapper` no longer reads `slots` at all
  (`MeetingResponse` doesn't expose them; see the API routes section), so adding it back would just
  be dead eager-fetching. `MeetingService.confirm()`/`cancel()` still mutate `meeting.getSlots()`,
  but always from inside the active transaction, where lazy loading works regardless of what's in
  the graph — the graph only matters for what gets read *after* the transaction ends.
- **A JPQL bind parameter used only in a bare `(:param is null or ...)` check breaks on real
  Postgres, but not on H2.** Postgres's extended query protocol resolves a parameter's type at
  parse time from its surrounding SQL; a standalone `? IS NULL` with no comparison/cast gives it
  nothing to infer from, and it fails with `could not determine data type of parameter $n`. H2
  doesn't enforce this, so the whole test suite (H2-backed) passes while the exact same query 500s
  against `docker-compose`'s Postgres. Fix is an explicit `cast(:param as <type>)` in the null-check
  branch — see `SlotRepository.search` and `existsOverlap` for the pattern. Any new optional-filter
  query with this shape needs the same treatment, and should be smoke-tested against
  `docker-compose up`, not just `mvn test`. (`findFreeSlotsCovering` has no optional/nullable
  parameters, so it isn't at risk of this — but it was still smoke-tested against Postgres directly,
  see `design-decisions-v2.md`.)

### API routes

All declared in `SlotRouterConfig`:

```
GET    /api/users                                             list users (paginated)
GET    /api/users/{userId}                                    get user
POST   /api/users                                              create user (also creates their Calendar)

GET    /api/users/{userId}/slots                              list all slots (paginated)
QUERY  /api/users/{userId}/slots                              filter slots (status, from, to in body; paginated)
POST   /api/users/{userId}/slots                              bulk-create slots (startTimes[] in body)
GET    /api/users/{userId}/slots/{slotId}                     get slot
PATCH  /api/users/{userId}/slots/{slotId}                     update slot (startTime, status)
DELETE /api/users/{userId}/slots/{slotId}                     delete slot

POST   /api/meetings                                          propose a meeting (PROPOSED)
GET    /api/meetings/{meetingId}                               get meeting
DELETE /api/meetings/{meetingId}                               cancel meeting (organizer only; body: {userId})
POST   /api/meetings/{meetingId}/participants/{userId}/vote   cast a vote (body: {vote})
QUERY  /api/meetings/availability                              find free windows across users (userIds, from, to in body)
```

`MeetingHandler.availability`/`MeetingService.availability` are the one route where a `QUERY` body's
fields are all *required* — it goes through `RequestValidator.parseAndValidate`, not
`SlotHandler.parseFilter`'s "empty body means no filter" convention, since there's no sensible
default for "find availability" the way an absent slot filter sensibly means "list everything." See
design-decisions-v5.md.

### Pagination

Every list/query endpoint (`UserHandler.listAll`, `SlotHandler.listAll`/`query`) is paginated via
`RequestValidator.parsePageable(request, sort)` — `page`/`size` query params, defaulting to
`page=0`/`size=20`, size capped at 100 (400 if out of range, not silently clamped) — and returns
[`PageResponse<T>`](src/main/java/io/irn/minidoodle/web/dto/PageResponse.java) instead of a bare
array.

**`SlotRepository.search()` doesn't exist anymore — it's `searchIds()` + `findByIdInWithMeetings()`,
deliberately two queries.** Pagination (LIMIT/OFFSET) and a `@ManyToMany` fetch join
(`@EntityGraph(attributePaths = "meetings")`, needed because `SlotMapper` reads
`slot.getMeetings()` with `open-in-view: false`) don't combine safely: with a collection fetch-joined
in the same query, Hibernate can't apply LIMIT/OFFSET in SQL (a fetch join can multiply rows), so it
silently paginates the *entire* result set in memory instead — exactly defeating the point of
paginating a "thousands of slots" endpoint. `searchIds()` selects just the page's ids with real
SQL-level pagination (no fetch join, so it's safe); `findByIdInWithMeetings()` loads those specific
entities with `meetings` eagerly fetched (safe here — no LIMIT/OFFSET on this query, the id list
already fixes which rows come back). `SlotService.query()` composes the two and wraps the result in
a `PageImpl` using the first query's total count. Any new paginated query needing an eager collection
needs the same two-step shape — don't add `@EntityGraph` directly to a `Pageable`-accepting query.

### Tests

| Layer | Classes | Notes |
|---|---|---|
| Unit (Mockito, no Spring context) | `SlotServiceTest`, `MeetingServiceTest`, `RequestValidatorTest` | |
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
- 123 tests total as of the cross-participant availability pass (design-decisions-v5.md).

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

## Docs layout: README.md vs. query-method.md

`README.md` is written for a technical reviewer evaluating this as a take-home submission — it stays
focused on the scheduling app itself (domain, API, run/consume/test instructions) and only points at
`QUERY` in passing. [`query-method.md`](query-method.md) is the front door for everything specific to
the HTTP `QUERY` method: why it's used, how it's routed, the `Content-Length` gotcha, and a pointer
into the `/api-docs`/Swagger UI saga below. When adding anything QUERY-specific, put the durable
explanation in `query-method.md` (or the decision logs it points to), not in `README.md` — keep
README's QUERY footprint to the short pointer it already has.

## Prior spec-compliance review and design decision logs

Five files are **historical records**, not current TODO lists — don't re-fix anything they describe
as already fixed, and don't merge their content, they document five separate passes:

- [`spec-review.md`](spec-review.md): a pass that checked the *original* single-slot-conversion
  meeting model against `coding-challenge.md`, found four issues (dead bean validation, a TOCTOU
  race in slot creation, meetings not exposing participants, an O(n) collection load on every slot
  create), and fixed all four. Still worth reading before touching `SlotService`,
  `CalendarRepository`, or the `web/dto` validation wiring — it explains *why* those specific shapes
  exist (the locking pattern, the `Slot(Calendar, Instant, Instant)` constructor, etc.).
- [`design-decisions-v2.md`](design-decisions-v2.md): the later refactor to the current
  propose/vote/confirm meeting model (`MeetingParticipant`, `SlotDurationConfig`, bulk slot
  creation). Read it before touching `MeetingService.vote()`/`confirm()` — it documents an actual
  contradiction in the brief this design came from (whether a participant without free slots should
  block confirmation with a 409, or be silently skipped) and which behavior was implemented and why.
  It also covers the `/api-docs`/Swagger UI story for the `QUERY` route (the "Why QUERY itself has
  no live `/api-docs` entry" section) — springdoc/swagger-core's Java model has no way to represent
  a `query` operation, but `OpenApiQueryOperationFilter` post-processes the served JSON directly to
  expose it as a real OpenAPI 3.2 operation anyway, since this project's actual bundled Swagger UI
  (5.32.2) already ships frontend support for it. Read that section (and
  [`troubleshooting.md`](troubleshooting.md)) before touching `OpenApiQuerySupportConfig` /
  `OpenApiQueryOperationFilter` — it explains exactly what was tried and ruled out first, including
  a specific externally-proposed approach that turned out not to work.
- [`design-decisions-v3.md`](design-decisions-v3.md): the package/app rename
  (`dev.isidro.queryverb` → `io.irn.minidoodle`, app name → `minidoodle`) and the input-validation
  hardening that followed it — every path variable (`userId`/`slotId`/`meetingId`) now goes through
  `RequestValidator.parseId()` instead of a bare `Long.valueOf(pathVariable(...))`, and malformed/
  mistyped request bodies are mapped to 400 instead of falling through to a generic 500. Read it
  before adding a new handler with a path variable or reaching for `Long.valueOf(request
  .pathVariable(...))` directly — that's exactly the pattern this pass removed everywhere else.
- [`design-decisions-v4.md`](design-decisions-v4.md): pagination on every list/query endpoint (see
  the "Pagination" section above for the `searchIds()`/`findByIdInWithMeetings()` two-query shape)
  and Flyway for the docker-compose/Postgres profile (see "Schema management" above). Read it before
  changing `PageResponse`/`RequestValidator.parsePageable`, or before adding a migration.
- [`design-decisions-v5.md`](design-decisions-v5.md): `QUERY /api/meetings/availability` —
  cross-participant free-window search, added to close the one piece of core Doodle behavior
  (suggesting a time that works, rather than requiring one already chosen) the rest of the API
  didn't cover. Read it before touching `MeetingService.availability()` — it explains why the
  implementation reuses `SlotRepository.findFreeSlotsCovering` per user instead of a new query, and
  why grid-aligned, fixed-duration slots make that a plain set-membership walk rather than general
  interval-intersection math.
