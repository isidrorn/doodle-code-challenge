# Design decisions: rebrand to minidoodle + input-validation hardening

Two unrelated changes bundled into one pass because they were requested together. Companion to
[`spec-review.md`](spec-review.md) (v1) and [`design-decisions-v2.md`](design-decisions-v2.md) (v2)
— don't merge any of these, they document three separate passes.

## Rename: `dev.isidro.queryverb` → `io.irn.minidoodle`, app name → `minidoodle`

Mechanical, but touched nearly every file in the repo:

- Base package: `dev.isidro.queryverb` → `io.irn.minidoodle` (every `.java` file under
  `src/main`/`src/test`, moved via `git mv` to preserve history, then a package/import string
  replace — verified by full recompile + test run afterward, not just "the sed succeeded").
- Main class: `Application` → `MiniDoodleApplication` (`io.irn.minidoodle.MiniDoodleApplication`) —
  the more idiomatic `ProjectNameApplication` convention, and a literal reading of "rename the
  application."
- `pom.xml`: `groupId` → `io.irn`, `artifactId`/`name` → `minidoodle` (was `dev.isidro` /
  `spring-boot-http-query-verb-example`).
- `spring.application.name` (`application.yml`) → `minidoodle` — this is what shows up in every log
  line's `[...]` bracket and in Actuator's `/actuator/info`.
- `application-local.yml`'s H2 in-memory DB name → `minidoodle` (was `queryverb`) — purely cosmetic
  (an in-memory DB's name is never seen outside its own JDBC URL), but there's no reason for it to
  keep referencing the old identity once everything else doesn't.
- `docker-compose.yml` gained a top-level `name: minidoodle` — without it, Compose derives the
  project name (and therefore container/network/volume names) from the containing directory name,
  which is still `spring-boot-http-query-verb-example` on disk. This wasn't renamed — moving the
  actual working directory/repo folder is a bigger, more disruptive action than "rename the
  application" asked for, and doing it would have meant relocating a live git working tree with no
  explicit ask to do so. Compose's `name:` field decouples the container identity from that.
- Left alone, deliberately: `docker-compose.yml`'s `POSTGRES_DB: doodle` (already on-theme, not tied
  to the old name), and `OpenApiConfig`'s `Contact` (author's own name/GitHub handle — personal
  metadata, unrelated to the application's identity).

Verified by a full clean rebuild + test run (101/101 green — the higher count than earlier passes
includes the validation tests below) after the rename, and a live run confirming the `[minidoodle]`
logger tag and `io.irn.minidoodle.MiniDoodleApplication` `@SpringBootConfiguration` discovery message
actually appear, not just that the code compiles.

## Input-validation hardening: a bad-typed path parameter 500'd instead of 400ing

Reported directly: a request sent from Swagger UI's "Try it out" with a wrong parameter type
returned 500. Reproduced before touching anything, not assumed — `GET /api/users/string` (`string`
is literally Swagger UI's default placeholder value for a path parameter with no declared type or
example, which is exactly what this API's `@RouterOperation`-documented path parameters have,
[`design-decisions-v2.md`](design-decisions-v2.md) not having added `@Parameter` schema detail to
them) returned a bare 500 `ProblemDetail`.

**Root cause**: every handler parsed path variables with `Long.valueOf(request.pathVariable(...))`
directly. A `@RequestMapping`-based Spring MVC controller gets this validation for free — a path
variable that doesn't match its declared type triggers `MethodArgumentTypeMismatchException`, mapped
to 400 by Spring's own default exception handling. Functional routes have no equivalent: `Long
.valueOf(...)` throwing `NumberFormatException` was simply never caught anywhere, so it fell through
to the generic `catch (Exception ex)` in `RouterExceptionFilter`/`GlobalExceptionHandler`, both of
which map anything unrecognized to a bare 500. Confirmed via the actual stack trace (both filters
already logged at `ERROR` — a change from an earlier pass in this project's history) rather than
guessed: `NumberFormatException: For input string: "string"` at the exact `Long.valueOf` call site.

While reproducing this across every route, a second, related gap turned up: a request body with a
value that doesn't match its declared type (an invalid enum constant, or JSON that doesn't parse at
all) hit the exact same fallthrough — `HttpMessageNotReadableException` from `ServerRequest.body
(Class)`, uncaught, same generic 500. Same class of bug (missing-type-validation → 500 instead of
400), same fix shape, so fixed in the same pass rather than left for a follow-up report.

**Fix, path variables**: [`RequestValidator.parseId(request, name)`](src/main/java/io/irn/minidoodle/web/RequestValidator.java)
— centralizes what was three separate, duplicated `Long.valueOf(pathVariable(...))` call sites
(`SlotHandler`, `UserHandler`, `MeetingHandler`) into one method that catches `NumberFormatException`
and throws a `ResponseStatusException(BAD_REQUEST, "<name> must be a valid number, got '<value>'")`
instead. Every handler now goes through it — no direct `Long.valueOf(pathVariable(...))` calls remain
anywhere in `web/`. Deliberately does **not** reject non-positive numbers: `userId=-5` is
syntactically a valid `Long`, and whether it corresponds to a real row is a 404 concern for the
service layer, not something `parseId` should pre-judge. Verified this distinction holds:
`GET /api/users/-5` → 404 ("User not found: -5"), `GET /api/users/string` → 400.

**Fix, request bodies**: `RouterExceptionFilter` and `GlobalExceptionHandler` both gained an explicit
`catch (HttpMessageNotReadableException ex)` block, mapped to 400 with the innermost cause's message
(Jackson's own error — e.g. "not one of the values accepted for Enum class: [BUSY, FREE]" — is more
specific than `HttpMessageNotReadableException`'s own wrapper message, which is usually just "JSON
parse error: " plus a duplicate of the same text). This is deliberately a broad, defense-in-depth
catch — it covers malformed JSON syntax, wrong JSON types, and invalid enum values uniformly, for
every route that parses a body, not just the ones already going through `RequestValidator
.parseAndValidate` (`SlotHandler.update()`'s `SlotUpdateRequest`, which has no bean-validation
constraints and so was never routed through `RequestValidator` at all before this, still benefits
from this fix since it lives at the exception-handling layer, not the parsing call site).

**Deliberately not changed**: Jackson's default lenient type coercion (e.g. a JSON number `123`
successfully deserializing into a `String name` field as `"123"`) — this is normal, widely-relied-on
Jackson behavior, not a gap this bug report was about, and disabling it globally
(`MapperFeature.ALLOW_COERCION_OF_SCALARS`) would be a separate, broader change with its own
trade-offs not evaluated here.

Every new behavior verified live (both the original bug and the fix, via `curl`, not inferred from
reading the code) before any test was written, then covered by tests: `RequestValidatorTest` gained
six `parseId` cases (valid, negative, non-numeric, overflow, blank, exact message format); each
`*RouteIT` gained bad-path-variable and malformed-body cases for its own routes. Full suite: 101/101
(up from 84 before this pass) after both the rename and the validation fixes landed together.
