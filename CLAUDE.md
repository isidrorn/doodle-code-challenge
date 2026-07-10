# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Two things in one repo: a playground for the HTTP `QUERY` method
(`draft-ietf-httpbis-safe-method-w-body`) and a "mini Doodle" meeting-scheduling API that gives
`QUERY` something real to filter. Spring Boot 4.1, Java 21.

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

App starts on `http://localhost:8080`; `DataSeeder` seeds two users (Alice, Bob) with a few slots
each — check the startup logs for their generated `userId`s. Swagger UI at `/swagger-ui.html`,
Prometheus metrics at `/actuator/prometheus`. See `requests.md` for a full set of `curl` examples.

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

### Domain

```
User 1──1 Calendar 1──N Slot N──1 Meeting
```

- `Calendar` is domain-only — never exposed as a REST resource; slots are addressed through
  `/api/users/{userId}/slots`.
- **Cascade direction matters.** `User.calendar` (`@OneToOne(mappedBy = "owner", cascade = ALL)`)
  cascades down to `Calendar`, which cascades `ALL` to its `Slot`s. `Calendar.owner` (the owning
  side) has no cascade back up. Always persist through `userRepository.save(user)` when the user is
  new — saving from the `Calendar` or `Slot` side does not cascade upward and throws
  `TransientPropertyValueException`.
- `Slot` carries `@Version` for optimistic locking; a losing concurrent writer gets mapped to 409 by
  `RouterExceptionFilter`.
- `MeetingService.schedule()` additionally takes a `PESSIMISTIC_WRITE` lock
  (`SlotRepository.findByIdForUpdate`) on every slot involved, closing the gap between the
  availability check and the status update.
- `spring.jpa.open-in-view: false` is set deliberately. Any repository method whose result gets
  read after its `@Transactional` service method returns (e.g. a mapper touching a lazy
  `@OneToMany`) must eagerly fetch what it needs — see `CalendarRepository.findByOwnerId` and
  `MeetingRepository.findById`, both annotated `@EntityGraph(attributePaths = "...")` for exactly
  this reason. Don't "fix" a `LazyInitializationException` by re-enabling open-in-view.
- **A JPQL bind parameter used only in a bare `(:param is null or ...)` check breaks on real
  Postgres, but not on H2.** Postgres's extended query protocol resolves a parameter's type at
  parse time from its surrounding SQL; a standalone `? IS NULL` with no comparison/cast gives it
  nothing to infer from, and it fails with `could not determine data type of parameter $n`. H2
  doesn't enforce this, so the whole test suite (H2-backed) passes while the exact same query 500s
  against `docker-compose`'s Postgres. Fix is an explicit `cast(:param as <type>)` in the null-check
  branch — see `SlotRepository.search` and `existsOverlap` for the pattern. Any new optional-filter
  query with this shape needs the same treatment, and should be smoke-tested against
  `docker-compose up`, not just `mvn test`.

### API routes

All declared in `SlotRouterConfig`:

```
GET    /api/users                                  list users
GET    /api/users/{userId}                         get user
POST   /api/users                                  create user (also creates their Calendar)

GET    /api/users/{userId}/slots                   list all slots
QUERY  /api/users/{userId}/slots                   filter slots (status, from, to in body)
POST   /api/users/{userId}/slots                   create slot
GET    /api/users/{userId}/slots/{slotId}           get slot
PATCH  /api/users/{userId}/slots/{slotId}           update slot (status, times)
DELETE /api/users/{userId}/slots/{slotId}           delete slot

POST   /api/users/{userId}/slots/{slotId}/meeting   convert slot into a meeting
DELETE /api/users/{userId}/slots/{slotId}/meeting   cancel meeting (frees all participant slots)
GET    /api/meetings/{meetingId}                    get meeting
```

### Tests

| Layer | Classes | Notes |
|---|---|---|
| Unit (Mockito, no Spring context) | `SlotServiceTest`, `MeetingServiceTest` | |
| Repository (`@DataJpaTest`) | `SlotRepositoryTest` | |
| Integration (`@SpringBootTest`, `RANDOM_PORT`, H2) | `UserRouteIT`, `SlotRouteIT`, `MeetingRouteIT` | real socket via `TestRestTemplate`, not MockMvc |

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
