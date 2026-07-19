# Design decisions: web layer converted to standard `@RestController` (v6)

Companion to [`spec-review.md`](spec-review.md) and `design-decisions-v2..v5.md` — separate passes,
don't merge them. This one converts the entire web layer from WebMvc.fn functional routes
(`RouterFunction` + `HandlerFunction`, the shape every earlier pass worked within) to idiomatic
annotated controllers, and deletes the hand-rolled infrastructure the functional style had made
necessary. [`conventions.md`](../conventions.md) describes the resulting target state as the
normative reference.

## Why

An earlier iteration of this project had reasons to experiment with functional routing; the
scheduling API itself never did. Nothing in the brief needs `RouterFunction`, and the annotated
programming model gives back, for free, three things the functional style forced this codebase to
hand-roll (each documented as a real, live-reproduced bug in its day — see
[`spec-review.md`](spec-review.md) issue 1 and [`design-decisions-v3.md`](design-decisions-v3.md)):

| Hand-rolled (functional era) | Replaced by (annotated) |
|---|---|
| `RequestValidator.parseAndValidate` — `ServerRequest.body(Class)` never invokes a `Validator`, so bean validation had to be run manually | `@Valid @RequestBody` → `MethodArgumentNotValidException` → 400 |
| `RequestValidator.parseId` — `request.pathVariable(...)` is always a `String`, hand-parsed | typed `@PathVariable Long` → `MethodArgumentTypeMismatchException` → 400 |
| `RequestValidator.parsePageable` — hand-parsed `page`/`size` with range checks | `@RequestParam @Min/@Max int page, size` → built-in method validation → 400 |
| `RouterExceptionFilter` — `@RestControllerAdvice` can't intercept exceptions from a `HandlerFunction` (it targets `HandlerMethod` only), so error mapping needed a `HandlerFilterFunction` | `GlobalExceptionHandler` (`@RestControllerAdvice`), now the single interception point |
| springdoc workaround configs — functional routes need manual `@RouterOperation` metadata to document at all | springdoc's normal `@RestController` auto-documentation, with real DTO schemas |

Net effect on the tree: three controllers (`UserController`, `SlotController`,
`MeetingController`) replace three handlers plus the router config; `RequestValidator`,
`RouterExceptionFilter`, and the springdoc workaround configs are deleted outright.

## The API contract is unchanged — and the tests prove it

Same paths, same verbs, same status codes, same `ProblemDetail` error bodies, same message
formats (e.g. `"userId must be a valid Long, got 'abc'"`). The integration tests were the
executable contract for this: every `*RouteIT` case asserting behavior (as opposed to transport
mechanics) passed unmodified against the new controllers. Filtering a user's slots and the
cross-participant availability search are plain `GET`s with query parameters — filter params
optional on the slot list (absent = "no filter on that dimension"), all three availability
parameters required (`@NotEmpty userIds`, `from`, `to` — there's no sensible default for an
availability search; see [`design-decisions-v5.md`](design-decisions-v5.md)).

Full suite after conversion: 113 green (down from 123 — `RequestValidatorTest`'s 10 unit cases
were deleted with the class; the *behaviors* they covered are framework-provided now and remain
asserted over real HTTP by the bad-path-variable / malformed-body / pagination-bounds IT cases).
The 8-thread bulk-create concurrency test was rerun three times in a row — the locking under test
lives in `SlotService` and didn't change, but the request path in front of it did.

## One non-obvious wiring decision: extend `ResponseEntityExceptionHandler`

`GlobalExceptionHandler` keeps a catch-all `@ExceptionHandler(Exception.class)` → 500 so no
unhandled exception ever leaks a stack trace. But a naked catch-all in an advice class is a trap:
Spring MVC's own exceptions (`HttpRequestMethodNotSupportedException`,
`MissingServletRequestParameterException`, `HandlerMethodValidationException`, ...) would match it
too, turning what should be canonical 405s/400s into generic 500s — the advice runs *before* the
framework's default resolvers get a chance. Extending `ResponseEntityExceptionHandler` fixes this:
its inherited, more-specific handlers win over the catch-all for every standard framework
exception, and the overrides in this class only sharpen detail messages (joined field errors for
body validation, parameter-name-prefixed messages for parameter validation, Jackson's root-cause
message for unreadable bodies) on responses the parent would already produce with the right status.
`ResponseStatusException` (the service layer's error vehicle) and
`ObjectOptimisticLockingFailureException` (→ 409) keep explicit handlers — neither is in the
parent's repertoire.

`ProblemDetail.instance` is no longer set by hand anywhere: Spring's message conversion fills it in
from the request path automatically when serializing a `ProblemDetail` whose `instance` is null.
