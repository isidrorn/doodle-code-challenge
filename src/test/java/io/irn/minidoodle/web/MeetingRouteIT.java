package io.irn.minidoodle.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.irn.minidoodle.TestSupport;
import io.irn.minidoodle.domain.MeetingStatus;
import io.irn.minidoodle.domain.ParticipantRole;
import io.irn.minidoodle.domain.SlotStatus;
import io.irn.minidoodle.domain.Vote;
import io.irn.minidoodle.repository.CalendarRepository;
import io.irn.minidoodle.repository.MeetingParticipantRepository;
import io.irn.minidoodle.repository.MeetingRepository;
import io.irn.minidoodle.repository.SlotRepository;
import io.irn.minidoodle.repository.UserRepository;
import io.irn.minidoodle.web.dto.AvailabilityWindow;
import io.irn.minidoodle.web.dto.MeetingCancelRequest;
import io.irn.minidoodle.web.dto.MeetingCreateRequest;
import io.irn.minidoodle.web.dto.MeetingResponse;
import io.irn.minidoodle.web.dto.PageResponse;
import io.irn.minidoodle.web.dto.ParticipantResponse;
import io.irn.minidoodle.web.dto.SlotResponse;
import io.irn.minidoodle.web.dto.VoteRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class MeetingRouteIT {

    // 60-minute window == 2 slots on the default 30-minute grid
    static final Instant T0  = Instant.parse("2026-06-01T09:00:00Z");
    static final Instant MID = Instant.parse("2026-06-01T09:30:00Z");
    static final Instant T1  = Instant.parse("2026-06-01T10:00:00Z");

    @Autowired TestRestTemplate    restTemplate;
    @Autowired SlotRepository      slotRepository;
    @Autowired MeetingRepository   meetingRepository;
    @Autowired MeetingParticipantRepository meetingParticipantRepository;
    @Autowired CalendarRepository  calendarRepository;
    @Autowired UserRepository      userRepository;

    Long aliceId; // organizer
    Long bobId;   // required
    Long carolId; // optional

    @BeforeEach
    void seed() {
        TestSupport.cleanUp(slotRepository, meetingRepository, meetingParticipantRepository, calendarRepository, userRepository);
        aliceId = TestSupport.seedUser(userRepository, calendarRepository, "Alice", "alice@test.com");
        bobId   = TestSupport.seedUser(userRepository, calendarRepository, "Bob",   "bob@test.com");
        carolId = TestSupport.seedUser(userRepository, calendarRepository, "Carol", "carol@test.com");

        // Alice and Bob both have the full [T0,T1) window free, as two consecutive 30-min slots.
        TestSupport.seedSlot(slotRepository, calendarRepository, aliceId, T0, MID);
        TestSupport.seedSlot(slotRepository, calendarRepository, aliceId, MID, T1);
        TestSupport.seedSlot(slotRepository, calendarRepository, bobId, T0, MID);
        TestSupport.seedSlot(slotRepository, calendarRepository, bobId, MID, T1);
        // Carol has no slots at all — used to prove confirmation doesn't block on missing coverage.
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_returns201_proposedWithOrganizerAutoYes() {
        ResponseEntity<MeetingResponse> res = createMeeting(List.of(bobId), List.of(carolId));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MeetingResponse body = res.getBody();
        assertThat(body.status()).isEqualTo(MeetingStatus.PROPOSED);
        assertThat(body.participants()).hasSize(3);
        assertThat(participant(body, aliceId).role()).isEqualTo(ParticipantRole.ORGANIZER);
        assertThat(participant(body, aliceId).vote()).isEqualTo(Vote.YES);
        assertThat(participant(body, bobId).role()).isEqualTo(ParticipantRole.REQUIRED);
        assertThat(participant(body, bobId).vote()).isEqualTo(Vote.PENDING);
        assertThat(participant(body, carolId).role()).isEqualTo(ParticipantRole.OPTIONAL);
        assertThat(participant(body, carolId).vote()).isEqualTo(Vote.PENDING);
    }

    @Test
    void create_returns400_whenTitleBlank() {
        var req = new MeetingCreateRequest("", "D", aliceId, T0, T1, List.of(bobId), List.of());
        ResponseEntity<String> res = restTemplate.postForEntity("/api/meetings", req, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_returns400_whenTitleTooLong() {
        var req = new MeetingCreateRequest("x".repeat(151), "D", aliceId, T0, T1, List.of(bobId), List.of());
        ResponseEntity<String> res = restTemplate.postForEntity("/api/meetings", req, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_returns404_whenOrganizerMissing() {
        var req = new MeetingCreateRequest("Sync", "D", 9999L, T0, T1, List.of(bobId), List.of());
        ResponseEntity<String> res = restTemplate.postForEntity("/api/meetings", req, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void create_returns400_whenStartTimeNotGridAligned() {
        var req = new MeetingCreateRequest("Sync", "D", aliceId, T0.plusSeconds(60), T1, List.of(bobId), List.of());
        ResponseEntity<String> res = restTemplate.postForEntity("/api/meetings", req, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    void getMeeting_returns200() {
        Long meetingId = createMeeting(List.of(bobId), List.of()).getBody().id();

        ResponseEntity<MeetingResponse> res = restTemplate.getForEntity("/api/meetings/{id}", MeetingResponse.class, meetingId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().id()).isEqualTo(meetingId);
    }

    @Test
    void getMeeting_returns404_whenNotFound() {
        assertThat(restTemplate.getForEntity("/api/meetings/9999", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getMeeting_returns400_whenIdNotNumeric() {
        assertThat(restTemplate.getForEntity("/api/meetings/{id}", String.class, "not-a-number").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── vote ──────────────────────────────────────────────────────────────────

    @Test
    void vote_requiredYes_confirmsMeeting_andBooksFullyCoveredSlots() {
        Long meetingId = createMeeting(List.of(bobId), List.of(carolId)).getBody().id();

        ResponseEntity<MeetingResponse> res = vote(meetingId, bobId, Vote.YES);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().status()).isEqualTo(MeetingStatus.CONFIRMED);

        // Alice's and Bob's slots are now BUSY and linked to the meeting; Carol had none to book,
        // which — per the design log — doesn't block confirming the meeting for everyone else.
        ResponseEntity<PageResponse<SlotResponse>> aliceSlots = restTemplate.exchange(
                "/api/users/{uid}/slots", HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<PageResponse<SlotResponse>>() {}, aliceId);
        assertThat(aliceSlots.getBody().content()).allMatch(s -> s.status() == SlotStatus.BUSY);
        assertThat(aliceSlots.getBody().content()).allSatisfy(s -> assertThat(s.meetingIds()).containsExactly(meetingId));
    }

    @Test
    void vote_requiredNo_cancelsMeetingImmediately() {
        Long meetingId = createMeeting(List.of(bobId), List.of()).getBody().id();

        ResponseEntity<MeetingResponse> res = vote(meetingId, bobId, Vote.NO);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().status()).isEqualTo(MeetingStatus.CANCELLED);
    }

    @Test
    void vote_staysProposed_untilEveryRequiredParticipantVotesYes() {
        Long daveId = TestSupport.seedUser(userRepository, calendarRepository, "Dave", "dave@test.com");
        Long meetingId = createMeeting(List.of(bobId, daveId), List.of()).getBody().id();

        ResponseEntity<MeetingResponse> afterBob = vote(meetingId, bobId, Vote.YES);
        assertThat(afterBob.getBody().status()).isEqualTo(MeetingStatus.PROPOSED);

        ResponseEntity<MeetingResponse> afterDave = vote(meetingId, daveId, Vote.YES);
        assertThat(afterDave.getBody().status()).isEqualTo(MeetingStatus.CONFIRMED);
    }

    @Test
    void vote_returns409_whenMeetingNotProposed() {
        Long meetingId = createMeeting(List.of(bobId), List.of()).getBody().id();
        vote(meetingId, bobId, Vote.NO); // cancels it

        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/meetings/{mid}/participants/{uid}/vote", new VoteRequest(Vote.YES),
                String.class, meetingId, bobId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void vote_returns400_whenUserNotParticipant() {
        Long meetingId = createMeeting(List.of(bobId), List.of()).getBody().id();
        Long strangerId = TestSupport.seedUser(userRepository, calendarRepository, "Erin", "erin@test.com");

        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/meetings/{mid}/participants/{uid}/vote", new VoteRequest(Vote.YES),
                String.class, meetingId, strangerId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void vote_returns404_whenMeetingMissing() {
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/meetings/9999/participants/{uid}/vote", new VoteRequest(Vote.YES), String.class, bobId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void vote_returns400_whenMeetingIdNotNumeric() {
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/meetings/{mid}/participants/{uid}/vote", new VoteRequest(Vote.YES),
                String.class, "not-a-number", bobId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void vote_returns400_whenUserIdNotNumeric() {
        Long meetingId = createMeeting(List.of(bobId), List.of()).getBody().id();

        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/meetings/{mid}/participants/{uid}/vote", new VoteRequest(Vote.YES),
                String.class, meetingId, "not-a-number");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void vote_returns400_whenVoteIsNotAValidEnumValue() {
        Long meetingId = createMeeting(List.of(bobId), List.of()).getBody().id();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var req = new HttpEntity<>("{\"vote\":\"MAYBE\"}", headers);

        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/meetings/{mid}/participants/{uid}/vote", req, String.class, meetingId, bobId);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void cancel_proposedMeeting_returns204() {
        Long meetingId = createMeeting(List.of(bobId), List.of()).getBody().id();

        cancelMeeting(meetingId, aliceId, HttpStatus.NO_CONTENT);

        ResponseEntity<MeetingResponse> after = restTemplate.getForEntity("/api/meetings/{id}", MeetingResponse.class, meetingId);
        assertThat(after.getBody().status()).isEqualTo(MeetingStatus.CANCELLED);
    }

    @Test
    void cancel_returns403_whenCallerNotOrganizer() {
        Long meetingId = createMeeting(List.of(bobId), List.of()).getBody().id();

        cancelMeeting(meetingId, bobId, HttpStatus.FORBIDDEN);
    }

    @Test
    void cancel_returns409_whenAlreadyCancelled() {
        Long meetingId = createMeeting(List.of(bobId), List.of()).getBody().id();
        cancelMeeting(meetingId, aliceId, HttpStatus.NO_CONTENT);

        cancelMeeting(meetingId, aliceId, HttpStatus.CONFLICT);
    }

    @Test
    void cancel_confirmedMeeting_freesBookedSlots() {
        Long meetingId = createMeeting(List.of(bobId), List.of()).getBody().id();
        vote(meetingId, bobId, Vote.YES); // confirms + books Alice's and Bob's slots

        cancelMeeting(meetingId, aliceId, HttpStatus.NO_CONTENT);

        ResponseEntity<PageResponse<SlotResponse>> aliceSlots = restTemplate.exchange(
                "/api/users/{uid}/slots", HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<PageResponse<SlotResponse>>() {}, aliceId);
        assertThat(aliceSlots.getBody().content()).allMatch(s -> s.status() == SlotStatus.FREE);
        assertThat(aliceSlots.getBody().content()).allSatisfy(s -> assertThat(s.meetingIds()).isEmpty());
    }

    // ── availability ──────────────────────────────────────────────────────────

    @Test
    void availability_returns200_withWindowsTaggedByFreeUsers() {
        // Alice and Bob are both free [T0,T1); Carol has no slots at all in that window.
        ResponseEntity<AvailabilityWindow[]> res = doAvailabilityQuery(List.of(aliceId, bobId, carolId), T0, T1);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).hasSize(2);
        assertThat(res.getBody()[0].startTime()).isEqualTo(T0);
        assertThat(res.getBody()[0].freeUserIds()).containsExactlyInAnyOrder(aliceId, bobId);
        assertThat(res.getBody()[1].startTime()).isEqualTo(MID);
        assertThat(res.getBody()[1].freeUserIds()).containsExactlyInAnyOrder(aliceId, bobId);
    }

    @Test
    void availability_returnsEmpty_whenQueriedUserHasNoFreeSlots() {
        ResponseEntity<AvailabilityWindow[]> res = doAvailabilityQuery(List.of(carolId), T0, T1);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEmpty();
    }

    @Test
    void availability_returns400_whenFromNotGridAligned() {
        ResponseEntity<String> res = restTemplate.getForEntity(
                availabilityUri(List.of(aliceId), T0.plusSeconds(60).toString(), T1.toString()), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void availability_returns400_whenUserIdsEmpty() {
        ResponseEntity<String> res = restTemplate.getForEntity(
                availabilityUri(List.of(), T0.toString(), T1.toString()), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void availability_returns404_whenUserMissing() {
        ResponseEntity<String> res = restTemplate.getForEntity(
                availabilityUri(List.of(9999L), T0.toString(), T1.toString()), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void availability_returns400_whenFromUnparseable() {
        ResponseEntity<String> res = restTemplate.getForEntity(
                availabilityUri(List.of(aliceId), "not-a-date", T1.toString()), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** All three parameters are required — unlike the slot-list filters, there is no default. */
    @Test
    void availability_returns400_whenRequiredParamMissing() {
        ResponseEntity<String> res = restTemplate.getForEntity(
                "/api/meetings/availability?from={from}&to={to}", String.class, T0, T1);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<AvailabilityWindow[]> doAvailabilityQuery(List<Long> userIds, Instant from, Instant to) {
        return restTemplate.getForEntity(
                availabilityUri(userIds, from.toString(), to.toString()), AvailabilityWindow[].class);
    }

    private String availabilityUri(List<Long> userIds, String from, String to) {
        String ids = String.join(",", userIds.stream().map(String::valueOf).toList());
        return "/api/meetings/availability?userIds=%s&from=%s&to=%s".formatted(ids, from, to);
    }

    private ResponseEntity<MeetingResponse> createMeeting(List<Long> required, List<Long> optional) {
        var req = new MeetingCreateRequest("Team sync", "Weekly", aliceId, T0, T1, required, optional);
        return restTemplate.postForEntity("/api/meetings", req, MeetingResponse.class);
    }

    private ResponseEntity<MeetingResponse> vote(Long meetingId, Long userId, Vote vote) {
        return restTemplate.postForEntity(
                "/api/meetings/{mid}/participants/{uid}/vote", new VoteRequest(vote),
                MeetingResponse.class, meetingId, userId);
    }

    private void cancelMeeting(Long meetingId, Long callerUserId, HttpStatus expected) {
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/meetings/{id}/cancel", new MeetingCancelRequest(callerUserId), String.class, meetingId);

        assertThat(res.getStatusCode()).isEqualTo(expected);
    }

    private ParticipantResponse participant(MeetingResponse response, Long userId) {
        return response.participants().stream()
                .filter(p -> p.userId().equals(userId))
                .findFirst().orElseThrow();
    }
}
