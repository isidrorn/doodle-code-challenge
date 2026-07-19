# Design decisions: input-validation hardening (v3)

Companion to [`spec-review.md`](spec-review.md) (v1) and
[`design-decisions-v2.md`](design-decisions-v2.md) (v2) — don't merge any of these, they document
separate passes. The web-layer mechanisms named below (`RequestValidator`,
`RouterExceptionFilter`, hand-parsed path variables) belong to the functional-routing
implementation the API used at the time; [`design-decisions-v6.md`](design-decisions-v6.md)
documents their later replacement by standard `@RestController` machinery. The *behavioral
contract* this pass established — a bad-typed path parameter or malformed body is a 400 with a
specific message, never a 500; a well-formed id that matches no row is a 404, not a 400 — is
unchanged and still covered by the same integration tests.

## A bad-typed path parameter 500'd instead of 400ing

Reported directly: a request sent from Swagger UI's "Try it out" with a wrong parameter type
returned 500. Reproduced before touching anything, not assumed — `GET /api/users/string` (`string`
is literally Swagger UI's default placeholder value for a path parameter with no declared type or
example) returned a bare 500 `ProblemDetail`.

**Root cause**: every handler parsed path variables with `Long.valueOf(request.pathVariable(...))`
directly. A `@RequestMapping`-based Spring MVC controller gets this validation for free — a path
variable that doesn't match its declared type triggers `MethodArgumentTypeMismatchException`, mapped
to 400 by Spring's own default exception handling. The functional routes in use at the time had no
equivalent: `Long.valueOf(...)` throwing `NumberFormatException` was simply never caught anywhere,
so it fell through to the generic `catch (Exception ex)` in the exception-handling layer, which
maps anything unrecognized to a bare 500. Confirmed via the actual stack trace rather than guessed:
`NumberFormatException: For input string: "string"` at the exact `Long.valueOf` call site.

While reproducing this across every route, a second, related gap turned up: a request body with a
value that doesn't match its declared type (an invalid enum constant, or JSON that doesn't parse at
all) hit the exact same fallthrough — `HttpMessageNotReadableException`, uncaught, same generic
500. Same class of bug (missing-type-validation → 500 instead of 400), same fix shape, so fixed in
the same pass rather than left for a follow-up report.

**Fix, path variables**: `RequestValidator.parseId(request, name)` — centralized what was three
separate, duplicated `Long.valueOf(pathVariable(...))` call sites into one method that catches
`NumberFormatException` and throws a
`ResponseStatusException(BAD_REQUEST, "<name> must be a valid number, got '<value>'")` instead.
Deliberately does **not** reject non-positive numbers: `userId=-5` is syntactically a valid `Long`,
and whether it corresponds to a real row is a 404 concern for the service layer, not something
input parsing should pre-judge. Verified this distinction holds: `GET /api/users/-5` → 404
("User not found: -5"), `GET /api/users/string` → 400.

**Fix, request bodies**: the exception-handling layer gained an explicit
`catch (HttpMessageNotReadableException ex)`, mapped to 400 with the innermost cause's message
(Jackson's own error — e.g. "not one of the values accepted for Enum class: [BUSY, FREE]" — is more
specific than `HttpMessageNotReadableException`'s own wrapper message, which is usually just "JSON
parse error: " plus a duplicate of the same text). This is deliberately a broad, defense-in-depth
catch — it covers malformed JSON syntax, wrong JSON types, and invalid enum values uniformly, for
every route that parses a body, since it lives at the exception-handling layer, not the parsing
call site.

**Deliberately not changed**: Jackson's default lenient type coercion (e.g. a JSON number `123`
successfully deserializing into a `String name` field as `"123"`) — this is normal, widely-relied-on
Jackson behavior, not a gap this bug report was about, and disabling it globally
(`MapperFeature.ALLOW_COERCION_OF_SCALARS`) would be a separate, broader change with its own
trade-offs not evaluated here.

Every new behavior was verified live (both the original bug and the fix, via `curl`, not inferred
from reading the code) before any test was written, then covered by tests: unit cases for the id
parsing (valid, negative, non-numeric, overflow, blank, exact message format) and, in each
`*RouteIT`, bad-path-variable and malformed-body cases for its own routes — the IT-level cases are
the ones that survive to the current implementation unchanged.
