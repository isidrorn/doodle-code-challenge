package dev.isidro.queryverb.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Parses and bean-validates a request body for functional routes.
 *
 * <p>WebMvc.fn HandlerFunctions have no equivalent of {@code @Valid} on a
 * {@code @RequestBody} parameter — {@code ServerRequest.body(Class)} never invokes a
 * Validator, so the {@code @NotBlank}/{@code @NotNull}/etc. annotations on the DTOs
 * are otherwise never checked. This is the manual replacement, used wherever a
 * handler parses a validated request DTO.
 */
@Component
@RequiredArgsConstructor
public class RequestValidator {

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
}
