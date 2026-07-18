# Design decisions: pagination + Flyway

A short pass, two independent additions bundled because they were requested together. Companion to
[`spec-review.md`](spec-review.md), [`design-decisions-v2.md`](design-decisions-v2.md), and
[`design-decisions-v3.md`](design-decisions-v3.md) — don't merge them, separate passes.

## Pagination on every list/query endpoint

`GET /api/users` and `GET /api/users/{userId}/slots` (unfiltered and filtered alike) returned
unbounded arrays — flagged as a known gap in `spec-review.md` from the start, given the
brief's own "hundreds of users, thousands of slots" framing. Fixed properly rather than deferred
further:

- `page`/`size` query params (default `page=0`, `size=20`, max `size=100`) on every list endpoint,
  validated
  by `RequestValidator.parsePageable` — an out-of-range value 400s rather than silently clamping,
  since it's much more likely a client bug than an intentional "give me everything."
- Response shape changed from a bare JSON array to an envelope —
  `{content, page, size, totalElements, totalPages}` (`PageResponse<T>`) — so a client can tell
  whether more pages exist instead of guessing from array length.
- `SlotRepository.search()` became a two-query `searchIds()` + `findByIdInWithMeetings()` pair
  instead of one `@EntityGraph`-annotated paginated query. Pagination (LIMIT/OFFSET) and a
  `@ManyToMany` fetch join don't combine safely: Hibernate can't apply LIMIT/OFFSET in SQL once a
  collection is fetch-joined (a fetch join can multiply rows), so it falls back to paginating the
  *entire* result set in memory — which would have silently defeated the entire point of paginating
  a "thousands of slots" endpoint. The first query selects just the page's ids (safe, real SQL-level
  pagination); the second loads those specific entities with `meetings` eagerly fetched (safe to
  fetch-join here — no LIMIT/OFFSET on this query, the id list already fixes which rows come back).

Every existing test asserting a bare `SlotResponse[]`/`UserResponse[]` body was updated to the new
envelope; three new tests per list endpoint cover page/size behavior and the two validation-error
cases. Full suite: 109/109.

## Flyway, for the docker-compose/Postgres profile only

Schema was managed entirely by Hibernate's `ddl-auto: update` until now — fine for a take-home, not
how a real service should manage schema changes, and flagged as such in the README's "known
limitations" section. Two migrations, reconstructed from the actual schema history rather than a
single flattened snapshot:

- `V1__initial_schema.sql` — the schema as of the original submission's domain model (a `Meeting`
  aggregating `Slot`s via a single nullable FK on `slot`, no participants or voting yet).
  Reconstructed from the entity mapping at commit `a90cb8e` (the commit immediately before the v2
  domain rewrite), not invented.
- `V2__meeting_model_refactor.sql` — the ALTER/CREATE statements that transform that into the
  current propose/vote/confirm schema: adds `meeting.start_time`/`end_time`/`status`, creates
  `meeting_participant` and the `slot_meeting` join table, migrates existing `slot.meeting_id`
  values into `slot_meeting`, then drops that column.

**Worth being upfront about**: these migrations were authored now, in this pass — they don't reflect
Flyway actually having been in place from the first commit. Retrofitting versioned migrations onto
an app that used auto-DDL from the start is common and legitimate (plenty of real services start
exactly this way and formalize schema management once the shape stabilizes), but it's a different
claim than "we used Flyway throughout," and this file exists partly so nobody has to guess which one
is true.

**Scoped to the default/docker-compose profile only** — `application.yml` now sets
`hibernate.ddl-auto: validate` (schema must already match; Flyway is what gets it there) and
`spring.flyway.enabled: true`. `application-local.yml` and `application-test.yml` both set
`spring.flyway.enabled: false` and keep `ddl-auto: create-drop`, unchanged: the migrations are
Postgres SQL, and running them against H2 (local dev, and the entire test suite) isn't worth the
maintenance cost of keeping two SQL dialects of every migration in sync for this project's size.

**A Spring Boot 4 gotcha hit while wiring this up, consistent with a few others this project has
already run into** (Jackson, `TestRestTemplate`): `flyway-core` alone
on the classpath is not enough. Boot 4 modularized Flyway's autoconfiguration out of
`spring-boot-autoconfigure` into its own `spring-boot-flyway`/`spring-boot-starter-flyway` module —
without it, `FlywayAutoConfiguration` never activates, migrations silently never run, and Hibernate's
`validate` mode then fails at startup with "missing table" for every entity. Confirmed live: first
attempt (just `flyway-core` + `flyway-database-postgresql`) failed exactly that way against a fresh
`docker-compose` Postgres volume; adding `spring-boot-starter-flyway` fixed it, verified by the
`flyway_schema_history` table showing both migrations applied and every subsequent request working
end-to-end (pagination, slot filtering, the full meeting propose → vote → confirm flow) against
the resulting schema.
