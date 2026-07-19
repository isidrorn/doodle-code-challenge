# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A "mini Doodle" meeting-scheduling API — the submission for a backend take-home challenge.
Spring Boot 4.1, Java 21. `spring.application.name` / Maven artifactId / base package are
`minidoodle` / `io.irn` / `io.irn.minidoodle`.

How we work — architecture, error handling, validation, the domain model, concurrency,
pagination, schema management, and testing conventions — is written up declaratively in
[`docs/conventions.md`](docs/conventions.md). **Read it before making changes**; if a change
alters a practice it describes, update it in the same commit. Requirement-by-requirement
traceability lives in [`docs/requirements-mapping.md`](docs/requirements-mapping.md). The
reasoning behind non-obvious design choices lives in the historical decision logs under
[`docs/decisions/`](docs/decisions/) — see `docs/conventions.md`'s "Git and documentation" section
for which log covers what before touching anything it flags (e.g. `MeetingService`,
`TimeGridConfig`, the web-layer error handling wiring).

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
[`docs/api-examples.md`](docs/api-examples.md) for a full set of `curl` examples, or run
`./docs/demo.sh` for a scripted end-to-end walkthrough against a live instance.

## Package layout

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

Layering is strictly one-directional — the web layer never touches a repository directly, always
through a service. Full detail on every layer, the API routes, and the domain model is in
[`docs/conventions.md`](docs/conventions.md) and [`README.md`](README.md).
