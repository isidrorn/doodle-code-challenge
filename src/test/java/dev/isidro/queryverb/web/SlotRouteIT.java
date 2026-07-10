package dev.isidro.queryverb.web;

import dev.isidro.queryverb.TestSupport;
import dev.isidro.queryverb.domain.SlotStatus;
import dev.isidro.queryverb.repository.CalendarRepository;
import dev.isidro.queryverb.repository.MeetingRepository;
import dev.isidro.queryverb.repository.SlotRepository;
import dev.isidro.queryverb.repository.UserRepository;
import dev.isidro.queryverb.web.dto.SlotCreateRequest;
import dev.isidro.queryverb.web.dto.SlotQueryFilter;
import dev.isidro.queryverb.web.dto.SlotResponse;
import dev.isidro.queryverb.web.dto.SlotUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class SlotRouteIT {

    static final HttpMethod QUERY = HttpMethod.valueOf("QUERY");

    static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");
    static final Instant T1 = Instant.parse("2026-06-01T10:00:00Z");
    static final Instant T2 = Instant.parse("2026-06-01T11:00:00Z");
    static final Instant T3 = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired TestRestTemplate    restTemplate;
    @Autowired SlotRepository      slotRepository;
    @Autowired MeetingRepository   meetingRepository;
    @Autowired CalendarRepository  calendarRepository;
    @Autowired UserRepository      userRepository;

    Long userId;
    Long slotId;

    @BeforeEach
    void seed() {
        TestSupport.cleanUp(slotRepository, meetingRepository, calendarRepository, userRepository);
        userId = TestSupport.seedUser(userRepository, calendarRepository, "Alice", "alice@test.com");
        slotId = TestSupport.seedSlot(slotRepository, calendarRepository, userId, T0, T1);
    }

    // ── GET (list) ────────────────────────────────────────────────────────────

    @Test
    void listSlots_returns200_withSeededSlot() {
        ResponseEntity<SlotResponse[]> res = restTemplate.getForEntity(
                "/api/users/{uid}/slots", SlotResponse[].class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).hasSize(1);
        assertThat(res.getBody()[0].status()).isEqualTo(SlotStatus.FREE);
        assertThat(res.getBody()[0].userId()).isEqualTo(userId);
    }

    // ── QUERY (filter by body) ────────────────────────────────────────────────

    @Test
    void querySlots_filtersByFreeStatus_returnsMatch() {
        ResponseEntity<SlotResponse[]> res = doQuery(userId, new SlotQueryFilter(SlotStatus.FREE, null, null));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).hasSize(1);
    }

    @Test
    void querySlots_filtersByBusyStatus_returnsEmpty() {
        ResponseEntity<SlotResponse[]> res = doQuery(userId, new SlotQueryFilter(SlotStatus.BUSY, null, null));

        assertThat(res.getBody()).isEmpty();
    }

    @Test
    void querySlots_filtersByTimeRange_returnsMatch() {
        TestSupport.seedSlot(slotRepository, calendarRepository, userId, T2, T3);  // second slot outside the filter range

        ResponseEntity<SlotResponse[]> res = doQuery(userId, new SlotQueryFilter(null, T0, T1));

        assertThat(res.getBody()).hasSize(1);
        assertThat(res.getBody()[0].startTime()).isEqualTo(T0);
    }

    /**
     * The core QUERY gotcha this project exists to demonstrate: some clients don't
     * send a body at all for a non-standard method. A genuinely empty body must
     * still be treated as "no filter", not rejected — see SlotHandler.parseFilter.
     */
    @Test
    void querySlots_withNoBody_treatedAsNoFilter_returnsAllSlots() {
        ResponseEntity<SlotResponse[]> res = restTemplate.exchange(
                "/api/users/{uid}/slots", QUERY,
                new HttpEntity<>(jsonHeaders()),
                SlotResponse[].class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).hasSize(1);
    }

    // ── GET (single) ──────────────────────────────────────────────────────────

    @Test
    void getSlot_returns200_whenFound() {
        ResponseEntity<SlotResponse> res = restTemplate.getForEntity(
                "/api/users/{uid}/slots/{sid}", SlotResponse.class, userId, slotId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().id()).isEqualTo(slotId);
        assertThat(res.getBody().userId()).isEqualTo(userId);
    }

    @Test
    void getSlot_returns404_whenNotFound() {
        ResponseEntity<String> res = restTemplate.getForEntity(
                "/api/users/{uid}/slots/9999", String.class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── POST (create) ─────────────────────────────────────────────────────────

    @Test
    void createSlot_returns201_andSlotIsFree() {
        ResponseEntity<SlotResponse> res = restTemplate.postForEntity(
                "/api/users/{uid}/slots", new SlotCreateRequest(T2, T3), SlotResponse.class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().status()).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void createSlot_returns409_whenOverlappingSlotExists() {
        // T0-T1 already seeded; try to create T0:30-T1:30 (overlapping)
        Instant overlap = T0.plus(30, ChronoUnit.MINUTES);
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/users/{uid}/slots",
                new SlotCreateRequest(overlap, T1.plus(30, ChronoUnit.MINUTES)),
                String.class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createSlot_returns400_whenStartTimeMissing() {
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/users/{uid}/slots", new SlotCreateRequest(null, T2), String.class, userId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Regression test for the TOCTOU race SlotService.create() used to have: the
     * overlap check and the insert weren't atomic, so two concurrent requests for
     * the same window could both pass the check before either committed. Fires N
     * concurrent requests for the exact same window and asserts the lock in
     * CalendarRepository.findByOwnerIdForUpdate serializes them down to exactly
     * one winner.
     */
    @Test
    void createSlot_concurrentOverlappingRequests_onlyOneSucceeds() throws Exception {
        var req = new SlotCreateRequest(T2, T3);
        int threadCount = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<HttpStatusCode>> futures = IntStream.range(0, threadCount)
                .<Future<HttpStatusCode>>mapToObj(i -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return restTemplate.postForEntity(
                            "/api/users/{uid}/slots", req, String.class, userId).getStatusCode();
                }))
                .toList();

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<HttpStatusCode> statuses = new ArrayList<>();
        for (Future<HttpStatusCode> f : futures) {
            statuses.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == HttpStatus.CONFLICT).hasSize(threadCount - 1);
    }

    // ── PATCH (update) ────────────────────────────────────────────────────────

    @Test
    void patchSlot_marksAsBusy() {
        HttpHeaders headers = jsonHeaders();
        var req = new HttpEntity<>(new SlotUpdateRequest(null, null, SlotStatus.BUSY), headers);

        ResponseEntity<SlotResponse> res = restTemplate.exchange(
                "/api/users/{uid}/slots/{sid}", HttpMethod.PATCH, req, SlotResponse.class, userId, slotId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().status()).isEqualTo(SlotStatus.BUSY);
    }

    @Test
    void patchSlot_returns409_whenRescheduleOverlapsAnotherSlot() {
        TestSupport.seedSlot(slotRepository, calendarRepository, userId, T2, T3);

        HttpHeaders headers = jsonHeaders();
        var req = new HttpEntity<>(new SlotUpdateRequest(T2, T3, null), headers);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/users/{uid}/slots/{sid}", HttpMethod.PATCH, req, String.class, userId, slotId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Test
    void deleteSlot_returns204_andSlotIsGone() {
        restTemplate.delete("/api/users/{uid}/slots/{sid}", userId, slotId);

        ResponseEntity<String> check = restTemplate.getForEntity(
                "/api/users/{uid}/slots/{sid}", String.class, userId, slotId);
        assertThat(check.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<SlotResponse[]> doQuery(Long uid, SlotQueryFilter filter) {
        HttpHeaders headers = jsonHeaders();
        return restTemplate.exchange(
                "/api/users/{uid}/slots", QUERY,
                new HttpEntity<>(filter, headers),
                SlotResponse[].class, uid);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return h;
    }
}
