# Conventions

This file is the **normative reference** for how code in this repository is written: architecture,
coding conventions, design principles, and working practices. It is declarative — "this is how we
do things" — not a decision log. The *why* behind non-obvious choices lives in the decision logs
([`spec-review.md`](spec-review.md), [`design-decisions-v2.md`](design-decisions-v2.md) through
v5), which are historical records and are never rewritten. When a practice changes, this file is
updated in the same commit as the change that breaks it.

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
  **Structural validation** (non-null, non-blank, well-formed email) belongs on the DTO;
  **business-rule validation** (grid alignment, overlap, state-machine legality) belongs in the
  service.
- Path variables are typed method parameters (`@PathVariable Long userId`) — Spring's type
  coercion turns malformed ids into 400s; no hand-rolled parsing.

### Error handling

- All errors are RFC 9457 `ProblemDetail` responses, produced centrally by
  `GlobalExceptionHandler` (`@RestControllerAdvice`). Handlers and services signal errors by
  throwing (`ResponseStatusException` or more specific exceptions) — they never build error
  responses themselves.
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
- Every collection endpoint is paginated: `page`/`size` query params (default `0`/`20`, size
  capped at 100, 400 when out of range) returning a `PageResponse<T>` envelope — never a bare
  array.
- Timestamps are `Instant`, serialized as ISO-8601 UTC. No local times, no time zones in the API.
- Server-derived values are never accepted from clients: `endTime` is always computed as
  `startTime + slotDurationMinutes` (a system-wide `@ConfigurationProperties` parameter, not
  per-slot data).
- Bulk operations are transactional all-or-nothing: one invalid entry rolls back the whole batch.
  No partial-success/207 semantics.

## Service layer

- `@Service @Transactional` classes are the only place business rules live. The transaction
  boundary is the service method — one use case, one transaction.
- State machines are driven inside a single service method (e.g. `MeetingService.vote()` handles
  vote → cancel/confirm transitions atomically), never spread across web-layer calls.
- Where the brief or the domain is ambiguous, the resolution is recorded in a decision log and the
  code points at it — see `design-decisions-v2.md` for the propose/vote/confirm contradiction and
  why confirmation silently skips participants without a free covering slot chain.

## Domain and persistence

- Entities carry behavior, not just fields: `slot.markBusy()`, `meeting.confirm()`,
  `meeting.addSlot()` (book + mark BUSY + associate as one operation). Services orchestrate;
  entities enforce their own local invariants.
- **Cascade direction is downward only**: `User` → `Calendar` → `Slot`. New object graphs are
  persisted via `userRepository.save(user)`; saving from the `Calendar` or `Slot` side does not
  cascade upward and fails.
- **Concurrency**:
  - `Slot` carries `@Version` (optimistic locking); a losing writer maps to 409.
  - Creating/moving slots takes a `PESSIMISTIC_WRITE` lock on the parent `Calendar` row, not on
    slot rows — a row lock can't close a phantom-read gap for a brand-new `INSERT`, so overlap
    check + write are serialized per calendar instead.
  - Concurrency-critical paths get real concurrency tests (multiple threads over real HTTP), not
    mocked approximations.
- `spring.jpa.open-in-view` is **false**, deliberately. Anything read after the transaction ends
  (mappers, typically) must be fetched eagerly by the repository method, via
  `@EntityGraph(attributePaths = ...)`. A `LazyInitializationException` is fixed by fetching what's
  needed — never by re-enabling open-in-view.
  - Caveat: `@EntityGraph` is a JPA *fetch graph* — associations not listed are demoted to LAZY
    even if their mapping says EAGER. Graphs list exactly what the post-transaction reader needs,
    nothing more.
- **Pagination + collection fetch joins never combine in one query.** Hibernate would silently
  paginate in memory. The pattern is two queries: page of ids with real SQL LIMIT/OFFSET, then load
  those entities with the collection fetched (`searchIds()` + `findByIdInWithMeetings()` in
  `SlotRepository` is the reference implementation).
- **JPQL and parameter typing**: a bind parameter used only in a bare `(:param is null or ...)`
  check fails on Postgres (parameter type can't be inferred) while passing on H2. Optional-filter
  parameters always get an explicit `cast(:param as <type>)` in the null-check branch, and any new
  query of this shape is verified against `docker-compose up`.
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
  failsafe plugin is configured). Touching locking code means re-running the concurrency IT
  several times — a broken lock shows up as occasional, not deterministic, failures.

## Git and documentation

- **Conventional commits**: `feat:`, `fix:`, `docs:`, with an optional scope (`fix(web): ...`),
  imperative mood, body explaining *why* when the subject line isn't enough.
- Documentation is layered by audience:
  - `README.md` — the front door for a reviewer: what this is, how to run it, how to consume the
    API, known limitations.
  - `conventions.md` (this file) — normative "how we work."
  - `spec-review.md` / `design-decisions-v*.md` — append-only historical records of review passes
    and design decisions. New decisions get a new log; old logs are never edited to match the
    present.
  - `api-examples.md` / `demo.sh` — runnable, copy-pasteable API walkthroughs, kept in sync with
    the actual routes.

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
