package io.irn.minidoodle.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Parses and validates untrusted request input for functional routes: bodies against bean
 * validation constraints, and path variables against their expected type.
 *
 * <p>WebMvc.fn HandlerFunctions have no equivalent of {@code @Valid} on a
 * {@code @RequestBody} parameter — {@code ServerRequest.body(Class)} never invokes a
 * Validator, so the {@code @NotBlank}/{@code @NotNull}/etc. annotations on the DTOs
 * are otherwise never checked. This is the manual replacement, used wherever a
 * handler parses a validated request DTO.
 *
 * <p>Path variables have an analogous gap: {@code @RequestMapping}-based Spring MVC controllers get
 * automatic 400s for a path variable that doesn't match its declared type (via {@code
 * MethodArgumentTypeMismatchException}), but a functional route's handler parses {@code
 * request.pathVariable(name)} — always a {@code String} — by hand. A handler that calls {@code
 * Long.valueOf(...)} directly lets a malformed id (e.g. Swagger UI's default `string` placeholder
 * for an untyped path parameter) throw an uncaught {@code NumberFormatException}, which the generic
 * exception handling in {@link RouterExceptionFilter}/{@link GlobalExceptionHandler} maps to a bare
 * 500 — not what a client did wrong. {@link #parseId} is the equivalent manual replacement: every
 * handler must go through it instead of calling {@code Long.valueOf(request.pathVariable(...))}
 * directly.
 */
@Component
@RequiredArgsConstructor
public class RequestValidator {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final Validator validator;

    public <T> T parseAndValidate(ServerRequest request, Class<T> type) throws Exception {
        T body = request.body(type);

        Set<ConstraintViolation<T>> violations = validator.validate(body);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining(", "));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }

        return body;
    }

    /**
     * Parses a path variable as a {@code Long} id, throwing a 400 (not letting a
     * {@code NumberFormatException} propagate to the generic 500 handler) if it isn't one.
     * Deliberately does not reject non-positive values — an id that's syntactically a valid number
     * but doesn't correspond to any row is a 404 concern for the service layer, not a 400 here.
     */
    public Long parseId(ServerRequest request, String pathVariableName) {
        String raw = request.pathVariable(pathVariableName);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "%s must be a valid number, got '%s'".formatted(pathVariableName, raw));
        }
    }

    /**
     * Parses {@code page}/{@code size} query params (both optional — default page 0, size
     * {@value #DEFAULT_PAGE_SIZE}), rejecting a negative page or a size outside
     * [1, {@value #MAX_PAGE_SIZE}] with 400 rather than silently clamping — an out-of-range value
     * is much more likely a client bug than an intentional request for "as much as you'll give me."
     */
    public Pageable parsePageable(ServerRequest request, Sort sort) {
        int page = parseIntParam(request, "page", 0);
        int size = parseIntParam(request, "size", DEFAULT_PAGE_SIZE);

        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0, got " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "size must be between 1 and %d, got %d".formatted(MAX_PAGE_SIZE, size));
        }
        return PageRequest.of(page, size, sort);
    }

    private int parseIntParam(ServerRequest request, String name, int defaultValue) {
        return request.param(name)
                .map(raw -> {
                    try {
                        return Integer.parseInt(raw);
                    } catch (NumberFormatException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "%s must be a valid integer, got '%s'".formatted(name, raw));
                    }
                })
                .orElse(defaultValue);
    }
}
