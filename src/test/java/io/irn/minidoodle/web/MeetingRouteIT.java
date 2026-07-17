package dev.isidro.queryverb.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.isidro.queryverb.TestSupport;
import dev.isidro.queryverb.domain.MeetingStatus;
import dev.isidro.queryverb.domain.ParticipantRole;
import dev.isidro.queryverb.domain.SlotStatus;
import dev.isidro.queryverb.domain.Vote;
import dev.isidro.queryverb.repository.CalendarRepository;
import dev.isidro.queryverb.repository.MeetingParticipantRepository;
import dev.isidro.queryverb.repository.MeetingRepository;
import dev.isidro.queryverb.repository.SlotRepository;
import dev.isidro.queryverb.repository.UserRepository;
import dev.isidro.queryverb.web.dto.MeetingCancelRequest;
import dev.isidro.queryverb.web.dto.MeetingCreateRequest;
import dev.isidro.queryverb.web.dto.MeetingResponse;
import dev.isidro.queryverb.web.dto.ParticipantResponse;
import dev.isidro.queryverb.web.dto.SlotResponse;
import dev.isidro.queryverb.web.dto.VoteRequest;
import java.time.Instant;
import java.util.List;
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

    // ── vote ──────────────────────────────────────────────────────────────────

    @Test
    void vote_requiredYes_confirmsMeeting_andBooksFullyCoveredSlots() {
        Long meetingId = createMeeting(List.of(bobId), List.of(carolId)).getBody().id();

        ResponseEntity<MeetingResponse> res = vote(meetingId, bobId, Vote.YES);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().status()).isEqualTo(MeetingStatus.CONFIRMED);

        // Alice's and Bob's slots are now BUSY and linked to the meeting; Carol had none to book,
        // which — per the design log — doesn't block confirming the meeting for everyone else.
        ResponseEntity<SlotResponse[]> aliceSlots = restTemplate.getForEntity(
                "/api/users/{uid}/slots", SlotResponse[].class, aliceId);
        assertThat(aliceSlots.getBody()).allMatch(s -> s.status() == SlotStatus.BUSY);
        assertThat(aliceSlots.getBody()).allSatisfy(s -> assertThat(s.meetingIds()).containsExactly(meetingId));
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

        ResponseEntity<SlotResponse[]> aliceSlots = restTemplate.getForEntity(
                "/api/users/{uid}/slots", SlotResponse[].class, aliceId);
        assertThat(aliceSlots.getBody()).allMatch(s -> s.status() == SlotStatus.FREE);
        assertThat(aliceSlots.getBody()).allSatisfy(s -> assertThat(s.meetingIds()).isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var req = new HttpEntity<>(new MeetingCancelRequest(callerUserId), headers);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/meetings/{id}", HttpMethod.DELETE, req, String.class, meetingId);

        assertThat(res.getStatusCode()).isEqualTo(expected);
    }

    private ParticipantResponse participant(MeetingResponse response, Long userId) {
        return response.participants().stream()
                .filter(p -> p.userId().equals(userId))
                .findFirst().orElseThrow();
    }
}
