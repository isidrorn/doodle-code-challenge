package io.irn.minidoodle.exception;

/**
 * Base of the business-rule exception hierarchy thrown by the service layer. Deliberately free of
 * any Spring Web type: services express *what* went wrong (not found, conflict, invalid input,
 * forbidden) and {@code GlobalExceptionHandler} owns the mapping to HTTP status codes — the
 * service layer stays reusable behind any delivery mechanism. See design-decisions-v8.md.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
