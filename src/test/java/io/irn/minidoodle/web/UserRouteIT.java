package io.irn.minidoodle.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.irn.minidoodle.TestSupport;
import io.irn.minidoodle.repository.CalendarRepository;
import io.irn.minidoodle.repository.MeetingParticipantRepository;
import io.irn.minidoodle.repository.MeetingRepository;
import io.irn.minidoodle.repository.SlotRepository;
import io.irn.minidoodle.repository.UserRepository;
import io.irn.minidoodle.web.dto.UserCreateRequest;
import io.irn.minidoodle.web.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class UserRouteIT {

    @Autowired TestRestTemplate    restTemplate;
    @Autowired SlotRepository      slotRepository;
    @Autowired MeetingRepository   meetingRepository;
    @Autowired MeetingParticipantRepository meetingParticipantRepository;
    @Autowired CalendarRepository  calendarRepository;
    @Autowired UserRepository      userRepository;

    @BeforeEach
    void cleanUp() {
        TestSupport.cleanUp(slotRepository, meetingRepository, meetingParticipantRepository, calendarRepository, userRepository);
    }

    @Test
    void listUsers_returnsEmptyList_whenNoUsers() {
        ResponseEntity<UserResponse[]> res = restTemplate.getForEntity("/api/users", UserResponse[].class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEmpty();
    }

    @Test
    void createUser_returns201_andCanBeRetrieved() {
        ResponseEntity<UserResponse> created = restTemplate.postForEntity(
                "/api/users", new UserCreateRequest("Alice", "alice@test.com"), UserResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long userId = created.getBody().id();

        ResponseEntity<UserResponse> fetched = restTemplate.getForEntity("/api/users/{id}", UserResponse.class, userId);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().email()).isEqualTo("alice@test.com");
    }

    @Test
    void getUser_returns404_whenNotFound() {
        assertThat(restTemplate.getForEntity("/api/users/9999", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listUsers_returnsAllSeeded() {
        TestSupport.seedUser(userRepository, calendarRepository, "Alice", "alice@test.com");
        TestSupport.seedUser(userRepository, calendarRepository, "Bob", "bob@test.com");

        assertThat(restTemplate.getForEntity("/api/users", UserResponse[].class).getBody()).hasSize(2);
    }

    @Test
    void createUser_returns400_whenNameBlank() {
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/users", new UserCreateRequest("", "alice@test.com"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createUser_returns400_whenEmailMalformed() {
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/users", new UserCreateRequest("Alice", "not-an-email"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getUser_returns400_whenIdNotNumeric() {
        // The exact bug this test guards against: Swagger UI's default placeholder for an
        // untyped path parameter is a non-numeric string, which used to reach Long.valueOf(...)
        // uncaught and 500 instead of a proper 400.
        ResponseEntity<String> res = restTemplate.getForEntity("/api/users/{id}", String.class, "not-a-number");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createUser_returns400_whenBodyIsMalformedJson() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var req = new HttpEntity<>("{not valid json", headers);

        ResponseEntity<String> res = restTemplate.postForEntity("/api/users", req, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
