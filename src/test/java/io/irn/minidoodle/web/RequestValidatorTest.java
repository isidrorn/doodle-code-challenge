package io.irn.minidoodle.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.irn.minidoodle.web.dto.UserCreateRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.ServerRequest;

@ExtendWith(MockitoExtension.class)
class RequestValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final RequestValidator requestValidator = new RequestValidator(validator);

    @Mock ServerRequest request;

    @Test
    void returnsBody_whenValid() throws Exception {
        var valid = new UserCreateRequest("Alice", "alice@test.com");
        when(request.body(UserCreateRequest.class)).thenReturn(valid);

        assertThat(requestValidator.parseAndValidate(request, UserCreateRequest.class)).isEqualTo(valid);
    }

    @Test
    void throwsBadRequest_whenBlankNameViolatesConstraint() throws Exception {
        var invalid = new UserCreateRequest("", "alice@test.com");
        when(request.body(UserCreateRequest.class)).thenReturn(invalid);

        assertThatThrownBy(() -> requestValidator.parseAndValidate(request, UserCreateRequest.class))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void throwsBadRequest_whenEmailMalformed() throws Exception {
        var invalid = new UserCreateRequest("Alice", "not-an-email");
        when(request.body(UserCreateRequest.class)).thenReturn(invalid);

        assertThatThrownBy(() -> requestValidator.parseAndValidate(request, UserCreateRequest.class))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── parseId ───────────────────────────────────────────────────────────────

    @Test
    void parseId_returnsLong_whenValidNumber() {
        when(request.pathVariable("userId")).thenReturn("42");

        assertThat(requestValidator.parseId(request, "userId")).isEqualTo(42L);
    }

    @Test
    void parseId_returnsLong_whenNegativeNumber() {
        // Syntactically valid — a nonexistent negative id is a 404 concern for the service layer,
        // not something parseId itself should reject.
        when(request.pathVariable("userId")).thenReturn("-5");

        assertThat(requestValidator.parseId(request, "userId")).isEqualTo(-5L);
    }

    @Test
    void parseId_throwsBadRequest_whenNotNumeric() {
        when(request.pathVariable("userId")).thenReturn("string");

        assertThatThrownBy(() -> requestValidator.parseId(request, "userId"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void parseId_throwsBadRequest_whenOverflowsLong() {
        when(request.pathVariable("userId")).thenReturn("99999999999999999999999");

        assertThatThrownBy(() -> requestValidator.parseId(request, "userId"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void parseId_throwsBadRequest_whenBlank() {
        when(request.pathVariable("userId")).thenReturn("");

        assertThatThrownBy(() -> requestValidator.parseId(request, "userId"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void parseId_messageIncludesParameterNameAndRawValue() {
        when(request.pathVariable("slotId")).thenReturn("abc");

        assertThatThrownBy(() -> requestValidator.parseId(request, "slotId"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getReason())
                .isEqualTo("slotId must be a valid number, got 'abc'");
    }
}
