package io.irn.minidoodle.web;

import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * HandlerFilterFunction applied to the functional RouterFunction bean.
 *
 * <p>@RestControllerAdvice does NOT intercept exceptions thrown from WebMvc.fn
 * HandlerFunctions — Spring MVC's ExceptionHandlerExceptionResolver works on
 * HandlerMethod (i.e. @Controller methods), not on HandlerFunction. This filter
 * is the correct interception point for functional routes.
 */
@Component
@Slf4j
public class RouterExceptionFilter {

    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        try {
            return next.handle(request);
        } catch (ResponseStatusException ex) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
            pd.setInstance(URI.create(request.uri().getPath()));
            return ServerResponse.status(ex.getStatusCode())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(pd);
        } catch (HttpMessageNotReadableException ex) {
            // Malformed JSON, a value that doesn't match its declared type, or an invalid enum
            // constant — request.body(Class) throws this directly (uncaught, it would otherwise
            // reach the generic 500 handler below). A malformed request body is a client error.
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Malformed request body: " + rootCauseMessage(ex));
            pd.setInstance(URI.create(request.uri().getPath()));
            return ServerResponse.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(pd);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.warn("Optimistic lock conflict at {}", request.uri());
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT, "The resource was modified concurrently — please retry.");
            pd.setProperty("type", "concurrent-modification");
            pd.setInstance(URI.create(request.uri().getPath()));
            return ServerResponse.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(pd);
        } catch (Exception ex) {
            log.error("Unhandled exception at {}", request.uri(), ex);
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                    HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
            pd.setInstance(URI.create(request.uri().getPath()));
            return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(pd);
        }
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
