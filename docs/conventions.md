# Conventions

This file is the **normative reference** for how code in this repository is written: architecture,
coding conventions, design principles, and working practices. It is declarative — "this is how we
do things" — not a decision log. The *why* behind non-obvious choices lives in the decision logs
under [`decisions/`](decisions/) ([`spec-review.md`](decisions/spec-review.md) through
[`design-decisions-v8.md`](decisions/design-decisions-v8.md) — see "Git and documentation" below
for which log covers what), which are historical records and are never rewritten. When a practice
changes, this file is updated in the same commit as the change that breaks it.

## Stack and baseline

- **Spring Boot 4.1, Java 21.** Build and run with JDK 21 exactly — Lombok's annotation processing
  silently produces nothing under newer JDKs, which surfaces as unrelated "cannot find symbol"
  errors.
- **Lombok** wherever it removes boilerplate (`@Getter`, `@Builder`, `@RequiredArgsConstructor`,
  `@Slf4j`); never where it would hide semantics (no `@Data` on JPA entities, no `@Setter` opening
  up fields the domain should control).
- **PostgreSQL** in the `docker-compose` (default) profile; **H2 in-memory** for the `local`
  profile and all tests. Anything that could behave differently between the two (see "JPQL and
  parameter typing" below) must be smoke-tested against Postgres, not just `mvn test`.
- Observability out of the box: springdoc/Swagger UI at `/swagger-ui.html`, Micrometer + Prometheus
  at `/actuator/prometheus`.

## Architecture

Layered, strictly one-directional. A layer only ever calls the layer directly below it:

```
web/          @RestController classes + dto/ (records) + mapper/ (components)
service/      @Service @Transactional — the only layer with business logic
repository/   Spring Data JpaRepository interfaces
domain/       JPA entities + enums — no dependency on any other layer
config/       configuration properties, seeding, OpenAPI config
```

- The web layer **never** touches a repository. All access goes through a service.
- The domain layer imports nothing from web, service, or repository.
- `Calendar` is a domain-internal concept — it is never exposed as a REST resource; slots are
  addressed through `/api/users/{userId}/slots`.

### Web layer

- One `@RestController` per aggregate (`UserController`, `SlotController`, `MeetingController`).
  Controllers are thin: parse/validate input, delegate to a service, map the result. No business
  logic, no conditionals beyond trivial request shaping.
- Request/response DTOs are Java **records** under `web/dto/`. Entities never cross the web
  boundary in either direction.
- Entity → DTO mapping lives in dedicated mapper components under `web/mapper/` — not in static
  factory methods on the DTOs, not inline in controllers.
- Bean validation via `@Valid @RequestBody` and constraint annotations on the request records.
  **Structural validation** (non-null, non-blank, well-formed email, `@Size` caps) belongs on the
  DTO; **business-rule validation** (grid alignment, overlap, state-machine legality) belongs in
  the service. Every free-text field carries a `@Size` cap that matches its DB column size
  exactly — an over-long value is a 400 at the boundary, never a database error. (`SlotUpdateRequest`
  is the one DTO with no constraints at all — every field optional — so its controller method skips
  `@Valid`.)
- Controller method parameters carry constraints directly (`@Min(0) int page`, `@Max(100) int size`,
  `@NotEmpty List<Long> userIds`) — Spring's built-in method validation raises
  `HandlerMethodValidationException` → 400, no `@Validated` needed. Out-of-range pagination is
  rejected with 400, never silently clamped.
- Path variables are typed method parameters (`@PathVariable Long userId`) — Spring's type
  coercion turns malformed ids into `MethodArgumentTypeMismatchException` → 400; no hand-rolled
  parsing.

### Error handling

- All errors are RFC 9457 `ProblemDetail` responses, produced centrally by
  `GlobalExceptionHandler` (`@RestControllerAdvice`). The service layer throws HTTP-agnostic
  domain exceptions (`NotFoundException`, `ConflictException`, `InvalidInputException`,
  `ForbiddenException` — `io.irn.minidoodle.exception`); the advice is the single place that maps
  each to a status code. No Spring Web type appears below the web layer — services stay reusable
  behind any delivery mechanism, and the status mapping lives where HTTP lives.
- `GlobalExceptionHandler` **extends `ResponseEntityExceptionHandler` deliberately**: the class has
  a catch-all `@ExceptionHandler(Exception.class)` → 500, and without the parent's inherited,
  more-specific handlers, that catch-all would swallow Spring MVC's own exceptions
  (`HttpRequestMethodNotSupportedException`, `HandlerMethodValidationException`,
  `MissingServletRequestParameterException`, ...) and turn canonical 405s/400s into 500s. The
  overrides in the class only sharpen detail messages — don't add a new `@ExceptionHandler` for an
  exception type the parent already handles (same-type handlers in one advice are an ambiguity
  error at startup); override the parent's protected method instead. The advice keeps a
  `ResponseStatusException` handler purely as a framework safety net — application code must never
  throw it.
- Status code discipline:
  - `400` — malformed or invalid input (including out-of-range pagination params: reject, don't
    silently clamp).
  - `403` — authenticated-shaped but not permitted (e.g. non-organizer cancelling a meeting).
  - `404` — resource genuinely absent.
  - `409` — valid request that conflicts with current state (overlap, optimistic-lock loser,
    slot held by a confirmed meeting, illegal state transition).
  - `500` — reserved for actual defects. A predictable bad input reaching a 500 is a bug.

### API design

- Plain REST, standard verbs. Reads are `GET` with query parameters; writes take a JSON body.
- `GET /api/meetings/availability` coexists with `GET /api/meetings/{meetingId}` because Spring's
  path matching always prefers the literal segment over the template — no custom ordering needed.
- Every collection endpoint is paginated: `page`/`size` query params (default `0`/`20`, size
  capped at 100, 400 when out of range) returning a `PageResponse<T>` envelope — never a bare
  array. `SlotService.query()` composes `searchIds()` + `findByIdInWithMeetings()` (see "Domain
  and persistence" below) into a `PageImpl` using the first query's total count — any new
  paginated query needing an eager collection needs the same two-step shape; don't add
  `@EntityGraph` directly to a `Pageable`-accepting query.
- Timestamps are `Instant`, serialized as ISO-8601 UTC. No local times, no time zones in the API.
- **Configuration validates; it never derives domain data.** The time grid (`TimeGridConfig`,
  `scheduling.time-grid-minutes`, default 30) requires every client-supplied boundary — slot
  start/end (bulk create and `PATCH`), meeting start/end, availability `from`/`to` — to satisfy
  `epochSecond % (gridMinutes * 60) == 0`, checked in `SlotService` and `MeetingService`. It's never
  used to compute or reinterpret stored values (no computed `endTime`), so changing it cannot
  corrupt existing rows — a status-only `PATCH` deliberately skips time validation for exactly this
  reason. Meetings book *whole* slots and only slots *fully contained* in the meeting window; a
  participant whose free slot overshoots the window is silently skipped. Slot intervals themselves
  are client-chosen data, validated at write time. See
  [`design-decisions-v7.md`](decisions/design-decisions-v7.md) before touching `TimeGridConfig`,
  slot creation/`PATCH` validation, or `MeetingService.availability()`.
- Bulk operations are transactional all-or-nothing: one invalid entry rolls back the whole batch.
  No partial-success/207 semantics.

## Service layer

- `@Service @Transactional` classes are the only place business rules live. The transaction
  boundary is the service method — one use case, one transaction.
- State machines are driven inside a single service method, never spread across web-layer calls. A
  `Meeting` is created `PROPOSED` with zero slots booked; each `MeetingParticipant` carries a
  `ParticipantRole` (`ORGANIZER`/`REQUIRED`/`OPTIONAL`) and a `Vote` (`PENDING`/`YES`/`NO`), with
  the organizer's vote implicitly `YES` from construction. `MeetingService.vote()` handles the
  transitions atomically: a `REQUIRED` participant voting `NO` cancels the meeting immediately;
  once every `REQUIRED` participant has voted `YES`, `MeetingService.confirm()` books each
  participant's `FREE` slots for the window *only if* `SlotRepository.findFreeSlotsCovering(...)`
  returns a full, gap-free chain covering `[startTime, endTime)` — a participant without one is
  skipped silently rather than blocking confirmation for everyone else.
  `Meeting.addSlot()` does the book-and-mark-BUSY-and-associate in one call; `Meeting.releaseSlots()`
  (used by `MeetingService.cancel()` on a `CONFIRMED` meeting) is the inverse.
- Where the brief or the domain is ambiguous, the resolution is recorded in a decision log and the
  code points at it — see [`design-decisions-v2.md`](decisions/design-decisions-v2.md) for the
  propose/vote/confirm contradiction and why confirmation silently skips participants without a
  free covering slot chain.

## Domain and persistence

- Entities carry behavior, not just fields: `slot.markBusy()`, `meeting.confirm()`,
  `meeting.addSlot()` (book + mark BUSY + associate as one operation). Services orchestrate;
  entities enforce their own local invariants.
- **Cascade direction is downward only**: `User` → `Calendar` → `Slot`. New object graphs are
  persisted via `userRepository.save(user)`; saving from the `Calendar` or `Slot` side does not
  cascade upward and fails.
- `Slot ─ Meeting` is `@ManyToMany` via the `slot_meeting` join table (owned by `Meeting`). A slot
  can sit in several `PROPOSED` meetings at once but at most one `CONFIRMED` one — not a DB
  constraint, enforced in code by `MeetingService.confirm()` only ever booking slots it already
  confirmed are `FREE`. `Slot.hasConfirmedMeeting()` is what `SlotService.update()`/`delete()` check
  before allowing a mutation (409 if true) — a slot in a merely `PROPOSED` meeting can still be
  freely edited, since nothing has actually reserved it yet.
- **Concurrency**:
  - `Slot` carries `@Version` (optimistic locking); a losing writer maps to 409.
  - Creating/moving slots takes a `PESSIMISTIC_WRITE` lock on the parent `Calendar` row, not on
    slot rows — a row lock can't close a phantom-read gap for a brand-new `INSERT`, so overlap
    check + write are serialized per calendar instead.
  - Concurrency-critical paths get real concurrency tests (multiple threads over real HTTP), not
    mocked approximations.
- `spring.jpa.open-in-view` is **false**, deliberately. Anything read after the transaction ends
  (mappers, typically) must be fetched eagerly by the repository method, via
  `@EntityGraph(attributePaths = ...)` — see `SlotRepository.findByIdInWithMeetings`/
  `findByUserIdAndSlotId` (`attributePaths = "meetings"`, since `SlotMapper` reads
  `slot.getMeetings()`) and `MeetingRepository.findById` (`attributePaths = {"participants",
  "participants.user"}`, since `MeetingMapper` reads `participant.getUser().getName()`). A
  `LazyInitializationException` is fixed by fetching what's needed — never by re-enabling
  open-in-view.
  - Caveat: `@EntityGraph` is a JPA *fetch graph* — associations not listed are demoted to LAZY
    even if their mapping says EAGER. This is why `MeetingRepository.findById()`'s graph omits
    `"slots"` — `MeetingMapper` doesn't read it — even though `MeetingService.confirm()`/`cancel()`
    still mutate `meeting.getSlots()` from inside the active transaction, where lazy loading works
    regardless of what's in the graph. Graphs list exactly what the post-transaction reader needs,
    nothing more.
- **Pagination + collection fetch joins never combine in one query.** Hibernate would silently
  paginate in memory. The pattern is two queries: page of ids with real SQL LIMIT/OFFSET, then load
  those entities with the collection fetched (`searchIds()` + `findByIdInWithMeetings()` in
  `SlotRepository` is the reference implementation).
- **JPQL and parameter typing**: a bind parameter used only in a bare `(:param is null or ...)`
  check fails on Postgres (its extended query protocol resolves a parameter's type at parse time
  from the surrounding SQL; a standalone `? IS NULL` gives it nothing to infer from) while passing
  on H2, which doesn't enforce this. Optional-filter parameters always get an explicit
  `cast(:param as <type>)` in the null-check branch (see `SlotRepository.searchIds`/`existsOverlap`),
  and any new query of this shape is verified against `docker-compose up`, not just `mvn test`.
- Hot-path awareness: per-request code paths must not force-load collections they don't need
  (e.g. new slots are constructed with the FK set directly rather than via `Calendar.addSlot()`,
  which would load every existing slot).

## Schema management

- **Flyway owns the schema** on the Postgres profile: migrations in
  `src/main/resources/db/migration` (`V{n}__description.sql`), with Hibernate on
  `ddl-auto: validate` so a mapping/migration mismatch fails fast at startup.
- `local` and `test` profiles (H2) use `ddl-auto: create-drop` with Flyway disabled — migrations
  are Postgres SQL.
- Consequence: any JPA mapping change ships with a new migration in the same commit, and is
  verified against `docker-compose up` — H2 profiles cannot catch a mismatch.
- See [`design-decisions-v4.md`](decisions/design-decisions-v4.md) for why `flyway-core` alone
  isn't enough to make Flyway's autoconfiguration activate on Spring Boot 4 — `spring-boot-starter-flyway`
  is needed too.

## Testing

Three layers, each testing what only it can test:

| Layer | Style | Scope |
|---|---|---|
| Unit | Mockito, no Spring context (`@ExtendWith(MockitoExtension.class)`) | service business rules, validation logic |
| Repository | `@DataJpaTest` (H2) | query correctness |
| Integration | `@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate` over a real socket | full HTTP contract: routing, validation wiring, error mapping, status codes |

- Integration tests use **no inheritance** — each `*RouteIT` class carries its own annotations
  (`@SpringBootTest`, `@AutoConfigureTestRestTemplate`, `@ActiveProfiles("test")`). Shared
  seed/cleanup helpers are static methods in `TestSupport`, called explicitly from `@BeforeEach`.
- Cleanup order respects FKs: slots → meetings → calendars → users.
- Seeded `Slot`s are saved via `slotRepository.save(slot)` — saving the already-managed `Calendar`
  merges to a detached copy and leaves the held `Slot` without an id.
- `DataSeeder` is excluded from tests (`@Profile("!test")`).
- Spring Boot 4 specifics: `@MockBean`/`@SpyBean` are gone (plain `@Mock` + Mockito extension);
  `TestRestTemplate` lives in `org.springframework.boot.resttestclient` and needs
  `@AutoConfigureTestRestTemplate` explicitly.
- Run matrix: `mvn test` runs `*Test` only; `mvn test -Dtest=*Test,*IT` runs everything (no
  failsafe plugin is configured). If you touch the locking in `SlotService.create()`/`update()`,
  re-run `SlotRouteIT.createSlots_concurrentOverlappingRequests_onlyOneSucceeds` specifically
  (`mvn test -Dtest=SlotRouteIT#createSlots_concurrentOverlappingRequests_onlyOneSucceeds`) a few
  times in a row — a broken lock only shows up as occasional extra `201`s, not a deterministic
  failure. 127 tests total as of the review-hardening pass
  ([`design-decisions-v8.md`](decisions/design-decisions-v8.md)); CI
  (`.github/workflows/ci.yml`) runs the full `*Test,*IT` pattern on every push — keep that pattern
  in sync if test naming conventions ever change.
- `spring-boot-restclient` is an explicit test dependency in `pom.xml` because
  `spring-boot-resttestclient` does not pull it in transitively, and
  `TestRestTemplateTestAutoConfiguration` needs `RestTemplateBuilder` on the classpath to even
  evaluate its conditions.

## Git and documentation

- **Conventional commits**: `feat:`, `fix:`, `docs:`, with an optional scope (`fix(web): ...`),
  imperative mood, body explaining *why* when the subject line isn't enough.
- Documentation is layered by audience:
  - [`README.md`](../README.md) — the front door for a reviewer: what this is, how to run it, how
    to consume the API, known limitations.
  - [`requirements-mapping.md`](requirements-mapping.md) — requirement-by-requirement
    traceability: what was asked, where it is implemented, which decision log argues it. Kept
    current, like this file, updated in the same commit as any change that alters
    requirement-visible behavior.
  - `conventions.md` (this file) — normative "how we work."
  - [`decisions/`](decisions/) — append-only historical records, one per review/design pass; never
    merged or edited to match the present, and don't re-fix anything a log describes as already
    fixed:
    - [`spec-review.md`](decisions/spec-review.md) (v1) — spec-compliance pass on the original
      single-slot meeting model: dead bean validation, the TOCTOU race and the calendar-level lock
      that fixed it, an O(n) collection load on every slot create.
    - [`design-decisions-v2.md`](decisions/design-decisions-v2.md) — the propose/vote/confirm
      meeting-model refactor; read before touching `MeetingService.vote()`/`confirm()` — it
      documents a real contradiction in the brief and which behavior won.
    - [`design-decisions-v3.md`](decisions/design-decisions-v3.md) — the rebrand to `minidoodle`
      and an input-validation hardening pass (a bad-typed path parameter 500ing instead of
      400ing).
    - [`design-decisions-v4.md`](decisions/design-decisions-v4.md) — pagination (the two-query
      shape above) and Flyway for the docker-compose/Postgres profile.
    - [`design-decisions-v5.md`](decisions/design-decisions-v5.md) — the cross-participant
      availability search; read before touching `MeetingService.availability()` — explains the
      per-user reuse of `findFreeSlotsCovering` and why grid-aligned fixed-duration slots reduced
      it to set membership at the time.
    - [`design-decisions-v6.md`](decisions/design-decisions-v6.md) — the conversion from
      WebMvc.fn functional routes to `@RestController`: which hand-rolled mechanisms were deleted
      and what replaced each, plus the `ResponseEntityExceptionHandler` wiring rationale. v1–v5
      reference the pre-v6 web machinery (`RequestValidator`, `RouterExceptionFilter`, handlers)
      in their historical context — that's intentional.
    - [`design-decisions-v7.md`](decisions/design-decisions-v7.md) — per-slot durations on a
      validation-only time grid, reversing v2's fixed-duration model; read before touching
      `TimeGridConfig`, slot creation/`PATCH` validation, or `MeetingService.availability()`. (v2–v5
      describe the fixed-duration era in their historical context; same rule, don't "fix" them.)
    - [`design-decisions-v8.md`](decisions/design-decisions-v8.md) — the pre-delivery
      review-hardening pass: domain exceptions replacing `ResponseStatusException`, the `POST
      /cancel` rationale, length caps + unique email (read its migration lesson before writing any
      migration that adds a constraint over existing data), and CI. (v1–v7 mention
      `ResponseStatusException`-throwing services and the DELETE cancel route in their historical
      context; same rule applies.)
  - [`api-examples.md`](api-examples.md) / [`demo.sh`](demo.sh) — runnable, copy-pasteable API
    walkthroughs, kept in sync with the actual routes.

## Engineering principles

- **Fix root causes, not symptoms.** A `LazyInitializationException`, a flaky lock test, or an H2
  vs. Postgres discrepancy gets diagnosed to its mechanism before any code changes.
- **Fail loudly at the boundary.** Invalid input is rejected with a 400 and a clear
  `ProblemDetail` — never silently clamped, defaulted, or half-accepted.
- **Prove it where it runs.** Tests pass on H2; the deliverable runs on Postgres. Anything
  DB-sensitive is verified against the real docker-compose stack before it counts as done.
- **Proportionality.** This is a scoped take-home, not a platform: no speculative abstractions, no
  interfaces with a single implementation, no configuration for things that don't vary. Complexity
  is spent only where the domain demands it (concurrency control, pagination correctness,
  state-machine integrity) — and where it is spent, it is documented.
- **Ambiguity gets a written decision.** When requirements conflict or underspecify, we pick a
  behavior, implement it consistently, and record the alternatives and rationale in a decision log
  rather than leaving the choice implicit in code.
