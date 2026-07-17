package io.irn.minidoodle.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fallback exception handler for @RestController endpoints.
 * Functional route exceptions are handled upstream by {@link RouterExceptionFilter},
 * which intercepts them before they reach DispatcherServlet's exception resolvers.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        pd.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.status(ex.getStatusCode()).body(pd);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMalformedBody(HttpMessageNotReadableException ex, HttpServletRequest req) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Malformed request body: " + cause.getMessage());
        pd.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                               HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The resource was modified concurrently — please retry.");
        pd.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception at {}", req.getRequestURI(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        pd.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.internalServerError().body(pd);
    }
}
