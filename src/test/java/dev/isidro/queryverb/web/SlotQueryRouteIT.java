package dev.isidro.queryverb.web;

import dev.isidro.queryverb.domain.Calendar;
import dev.isidro.queryverb.domain.Slot;
import dev.isidro.queryverb.domain.SlotStatus;
import dev.isidro.queryverb.domain.User;
import dev.isidro.queryverb.repository.CalendarRepository;
import dev.isidro.queryverb.web.dto.SlotQueryFilter;
import dev.isidro.queryverb.web.dto.SlotResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.resttestclient.TestRestTemplate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SlotQueryRouteIT {

    private static final HttpMethod QUERY = HttpMethod.valueOf("QUERY");

    @Autowired TestRestTemplate restTemplate;
    @Autowired CalendarRepository calendarRepository;

    private Long userId;

    @BeforeEach
    @Transactional
    void seed() {
        User user = new User("IT User", "it@test.dev");
        Calendar calendar = new Calendar(user);
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        calendar.addSlot(new Slot(now, now.plus(1, ChronoUnit.HOURS)));
        calendar.addSlot(new Slot(now.plus(2, ChronoUnit.HOURS), now.plus(3, ChronoUnit.HOURS)));
        calendarRepository.save(calendar);
        // mark second slot busy
        var busy = calendar.getSlots().get(1);
        busy.markBusy();
        calendarRepository.save(calendar);
        userId = user.getId();
    }

    @Test
    void queryWithNoFilterReturnsAll() {
        ResponseEntity<SlotResponse[]> response = doQuery(SlotQueryFilter.empty());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void queryFiltersByFreeStatus() {
        ResponseEntity<SlotResponse[]> response = doQuery(new SlotQueryFilter(SlotStatus.FREE, null, null));
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody()[0].status()).isEqualTo(SlotStatus.FREE);
    }

    private ResponseEntity<SlotResponse[]> doQuery(SlotQueryFilter filter) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return restTemplate.exchange(
                "/api/users/{userId}/slots", QUERY,
                new HttpEntity<>(filter, headers),
                SlotResponse[].class, userId);
    }
}
