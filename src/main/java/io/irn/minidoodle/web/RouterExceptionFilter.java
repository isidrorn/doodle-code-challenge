package dev.isidro.queryverb.web;

import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
}
