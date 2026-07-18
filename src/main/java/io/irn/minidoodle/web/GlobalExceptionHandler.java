package io.irn.minidoodle.web;

import io.irn.minidoodle.exception.ConflictException;
import io.irn.minidoodle.exception.DomainException;
import io.irn.minidoodle.exception.ForbiddenException;
import io.irn.minidoodle.exception.InvalidInputException;
import io.irn.minidoodle.exception.NotFoundException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Central exception → RFC 9457 {@link ProblemDetail} mapping for the whole API.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the standard Spring MVC exceptions
 * (method not supported, missing parameter, unknown path, ...) keep their canonical status codes
 * instead of falling through to the generic 500 catch-all below; the overrides only sharpen the
 * detail message of responses the parent would already produce. {@code ProblemDetail.instance}
 * is filled in from the request path automatically by Spring's message conversion.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * Business-rule failures thrown by the service layer. The services throw HTTP-agnostic
     * {@link DomainException} subtypes; this advice is the single place that knows which status
     * each one means — the status mapping is an HTTP concern, so it lives in the web layer.
     */
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidInputException.class)
    ResponseEntity<ProblemDetail> handleInvalidInput(InvalidInputException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ProblemDetail> handleForbidden(ForbiddenException ex) {
        return problem(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /**
     * Safety net only — application code never throws this anymore (services throw
     * {@link DomainException} subtypes), but framework code occasionally does, and without an
     * explicit handler the catch-all below would flatten it to a 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        return ResponseEntity.status(ex.getStatusCode()).body(pd);
    }

    /** A losing writer in an optimistic-lock conflict (Slot carries @Version). */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "The resource was modified concurrently — please retry.");
    }

    /**
     * DB constraint violations that slipped past the service-level checks — in practice the loser
     * of a concurrent race on the unique user email (the pre-check in UserService.create gives
     * the friendly message; this is the backstop the constraint guarantees).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "The request conflicts with existing data.");
    }

    /**
     * A path variable or query parameter that can't be converted to its declared type (non-numeric
     * id, unparseable date, invalid enum constant). More specific than the parent's
     * {@code TypeMismatchException} handling, so this message wins.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String requiredType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "value";
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "%s must be a valid %s, got '%s'".formatted(ex.getName(), requiredType, ex.getValue()));
        return ResponseEntity.badRequest().body(pd);
    }

    /** Bean-validation failures on a {@code @Valid @RequestBody} DTO. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message));
    }

    /** Constraint violations on controller method parameters (e.g. page/size bounds, @NotEmpty userIds). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName() + " "
                                + error.getDefaultMessage()))
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message));
    }

    /** Malformed JSON, a value that doesn't match its declared type, or an invalid enum constant. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Malformed request body: " + rootCauseMessage(ex)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleGeneric(Exception ex, WebRequest request) {
        log.error("Unhandled exception at {}", request.getDescription(false), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    /**
     * Jackson wraps its own (informative — "not one of the values accepted for Enum class:
     * [FREE, BUSY]", etc.) message inside {@code HttpMessageNotReadableException}'s cause chain
     * rather than its own {@code getMessage()}, which is usually just "JSON parse error: " plus a
     * duplicate of the cause. Prefer the innermost cause so the client sees the specific reason.
     */
    private static String rootCauseMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
