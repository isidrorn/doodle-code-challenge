package dev.isidro.queryverb.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import dev.isidro.queryverb.web.dto.UserCreateRequest;
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
}
