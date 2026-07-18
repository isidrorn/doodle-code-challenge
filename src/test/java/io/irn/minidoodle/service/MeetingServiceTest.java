package io.irn.minidoodle.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.irn.minidoodle.config.TimeGridConfig;
import io.irn.minidoodle.exception.ConflictException;
import io.irn.minidoodle.exception.DomainException;
import io.irn.minidoodle.exception.ForbiddenException;
import io.irn.minidoodle.exception.InvalidInputException;
import io.irn.minidoodle.exception.NotFoundException;
import io.irn.minidoodle.domain.Calendar;
import io.irn.minidoodle.domain.Meeting;
import io.irn.minidoodle.domain.MeetingParticipant;
import io.irn.minidoodle.domain.MeetingStatus;
import io.irn.minidoodle.domain.ParticipantRole;
import io.irn.minidoodle.domain.Slot;
import io.irn.minidoodle.domain.User;
import io.irn.minidoodle.domain.Vote;
import io.irn.minidoodle.repository.MeetingParticipantRepository;
import io.irn.minidoodle.repository.MeetingRepository;
import io.irn.minidoodle.repository.SlotRepository;
import io.irn.minidoodle.repository.UserRepository;
import io.irn.minidoodle.web.dto.AvailabilityWindow;
import io.irn.minidoodle.web.dto.MeetingCreateRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

    @Mock SlotRepository slotRepository;
    @Mock MeetingRepository meetingRepository;
    @Mock MeetingParticipantRepository meetingParticipantRepository;
    @Mock UserRepository userRepository;

    MeetingService meetingService;

    static final Long ORGANIZER_ID = 1L;
    static final Long REQUIRED_ID  = 2L;
    static final Long OPTIONAL_ID  = 3L;
    static final Long MEETING_ID   = 100L;
    // 60-minute window == 2 slots on the default 30-minute grid
    static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");
    static final Instant MID = Instant.parse("2026-06-01T09:30:00Z");
    static final Instant T1 = Instant.parse("2026-06-01T10:00:00Z");

    User organizer;
    User required;
    User optional;

    @BeforeEach
    void setUp() {
        meetingService = new MeetingService(slotRepository, meetingRepository,
                meetingParticipantRepository, userRepository, new TimeGridConfig(30));

        organizer = userWithId(ORGANIZER_ID, "Alice");
        required  = userWithId(REQUIRED_ID, "Bob");
        optional  = userWithId(OPTIONAL_ID, "Carol");
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_throwsBadRequest_whenStartAfterEnd() {
        assertThrown(InvalidInputException.class, () -> meetingService.create(request(T1, T0)));
    }

    @Test
    void create_throwsBadRequest_whenStartNotGridAligned() {
        assertThrown(InvalidInputException.class, () -> meetingService.create(request(T0.plusSeconds(1), T1)));
    }

    @Test
    void create_throwsBadRequest_whenEndNotGridAligned() {
        assertThrown(InvalidInputException.class, () -> meetingService.create(request(T0, T0.plusSeconds(600))));
    }

    @Test
    void create_throwsNotFound_whenOrganizerMissing() {
        when(userRepository.findById(ORGANIZER_ID)).thenReturn(Optional.empty());
        assertThrown(NotFoundException.class, () -> meetingService.create(request(T0, T1)));
    }

    @Test
    void create_throwsNotFound_whenRequiredParticipantMissing() {
        when(userRepository.findById(ORGANIZER_ID)).thenReturn(Optional.of(organizer));
        when(userRepository.findById(REQUIRED_ID)).thenReturn(Optional.empty());
        assertThrown(NotFoundException.class, () -> meetingService.create(request(T0, T1)));
    }

    @Test
    void create_savesMeeting_withOrganizerAutoYes_andParticipantsPending() {
        when(userRepository.findById(ORGANIZER_ID)).thenReturn(Optional.of(organizer));
        when(userRepository.findById(REQUIRED_ID)).thenReturn(Optional.of(required));
        when(userRepository.findById(OPTIONAL_ID)).thenReturn(Optional.of(optional));
        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Meeting result = meetingService.create(request(T0, T1));

        assertThat(result.getStatus()).isEqualTo(MeetingStatus.PROPOSED);
        assertThat(result.getParticipants()).hasSize(3);
        assertThat(participantFor(result, ORGANIZER_ID).getRole()).isEqualTo(ParticipantRole.ORGANIZER);
        assertThat(participantFor(result, ORGANIZER_ID).getVote()).isEqualTo(Vote.YES);
        assertThat(participantFor(result, REQUIRED_ID).getRole()).isEqualTo(ParticipantRole.REQUIRED);
        assertThat(participantFor(result, REQUIRED_ID).getVote()).isEqualTo(Vote.PENDING);
        assertThat(participantFor(result, OPTIONAL_ID).getRole()).isEqualTo(ParticipantRole.OPTIONAL);
        assertThat(participantFor(result, OPTIONAL_ID).getVote()).isEqualTo(Vote.PENDING);
    }

    @Test
    void create_dedupesParticipant_whenOrganizerAlsoListedAsRequired() {
        when(userRepository.findById(ORGANIZER_ID)).thenReturn(Optional.of(organizer));
        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var req = new MeetingCreateRequest("Sync", "D", ORGANIZER_ID, T0, T1, List.of(ORGANIZER_ID), List.of());

        Meeting result = meetingService.create(req);

        assertThat(result.getParticipants()).hasSize(1);
    }

    // ── vote ──────────────────────────────────────────────────────────────────

    @Test
    void vote_throwsNotFound_whenMeetingMissing() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.empty());
        assertThrown(NotFoundException.class, () -> meetingService.vote(MEETING_ID, REQUIRED_ID, Vote.YES));
    }

    @Test
    void vote_throwsNotFound_whenUserMissing() {
        Meeting meeting = proposedMeeting();
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(userRepository.findById(REQUIRED_ID)).thenReturn(Optional.empty());

        assertThrown(NotFoundException.class, () -> meetingService.vote(MEETING_ID, REQUIRED_ID, Vote.YES));
    }

    @Test
    void vote_throwsConflict_whenMeetingNotProposed() {
        Meeting meeting = proposedMeeting();
        meeting.cancel();
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(userRepository.findById(REQUIRED_ID)).thenReturn(Optional.of(required));

        assertThrown(ConflictException.class, () -> meetingService.vote(MEETING_ID, REQUIRED_ID, Vote.YES));
    }

    @Test
    void vote_throwsBadRequest_whenUserNotParticipant() {
        Meeting meeting = proposedMeeting();
        User stranger = userWithId(999L, "Stranger");
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(userRepository.findById(999L)).thenReturn(Optional.of(stranger));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(MEETING_ID, 999L)).thenReturn(Optional.empty());

        assertThrown(InvalidInputException.class, () -> meetingService.vote(MEETING_ID, 999L, Vote.YES));
    }

    @Test
    void vote_requiredVotesNo_cancelsMeetingImmediately() {
        Meeting meeting = proposedMeeting();
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(userRepository.findById(REQUIRED_ID)).thenReturn(Optional.of(required));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(MEETING_ID, REQUIRED_ID))
                .thenReturn(Optional.of(participantFor(meeting, REQUIRED_ID)));
        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Meeting result = meetingService.vote(MEETING_ID, REQUIRED_ID, Vote.NO);

        assertThat(result.getStatus()).isEqualTo(MeetingStatus.CANCELLED);
    }

    @Test
    void vote_optionalVote_doesNotConfirm_whenRequiredStillPending() {
        Meeting meeting = proposedMeeting();
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(userRepository.findById(OPTIONAL_ID)).thenReturn(Optional.of(optional));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(MEETING_ID, OPTIONAL_ID))
                .thenReturn(Optional.of(participantFor(meeting, OPTIONAL_ID)));
        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Meeting result = meetingService.vote(MEETING_ID, OPTIONAL_ID, Vote.YES);

        assertThat(result.getStatus()).isEqualTo(MeetingStatus.PROPOSED);
    }

    @Test
    void vote_allRequiredYes_confirmsMeeting_andBooksFullyCoveredSlots() {
        Meeting meeting = proposedMeeting();

        Slot organizerSlot1 = slotOwnedBy(ORGANIZER_ID, T0, MID);
        Slot organizerSlot2 = slotOwnedBy(ORGANIZER_ID, MID, T1);
        Slot requiredSlot1  = slotOwnedBy(REQUIRED_ID, T0, MID);
        Slot requiredSlot2  = slotOwnedBy(REQUIRED_ID, MID, T1);

        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(userRepository.findById(REQUIRED_ID)).thenReturn(Optional.of(required));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(MEETING_ID, REQUIRED_ID))
                .thenReturn(Optional.of(participantFor(meeting, REQUIRED_ID)));
        when(slotRepository.findFreeSlotsCovering(ORGANIZER_ID, T0, T1))
                .thenReturn(List.of(organizerSlot1, organizerSlot2));
        when(slotRepository.findFreeSlotsCovering(REQUIRED_ID, T0, T1))
                .thenReturn(List.of(requiredSlot1, requiredSlot2));
        when(slotRepository.findFreeSlotsCovering(OPTIONAL_ID, T0, T1))
                .thenReturn(List.of());
        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Meeting result = meetingService.vote(MEETING_ID, REQUIRED_ID, Vote.YES);

        assertThat(result.getStatus()).isEqualTo(MeetingStatus.CONFIRMED);
        assertThat(organizerSlot1.isFree()).isFalse();
        assertThat(organizerSlot2.isFree()).isFalse();
        assertThat(requiredSlot1.isFree()).isFalse();
        assertThat(requiredSlot2.isFree()).isFalse();
        assertThat(result.getSlots())
                .containsExactlyInAnyOrder(organizerSlot1, organizerSlot2, requiredSlot1, requiredSlot2);
    }

    /**
     * Resolves the brief's internal contradiction in favor of the MeetingService pseudocode
     * (silent skip + confirm anyway) over the vote endpoint's stated 409 — see the design log.
     */
    @Test
    void vote_allRequiredYes_confirms_butSkipsParticipantWithoutFullCoverage() {
        Meeting meeting = proposedMeeting();

        // Only half the window free — not a full, contiguous cover.
        Slot organizerSlot1 = slotOwnedBy(ORGANIZER_ID, T0, MID);

        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(userRepository.findById(REQUIRED_ID)).thenReturn(Optional.of(required));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(MEETING_ID, REQUIRED_ID))
                .thenReturn(Optional.of(participantFor(meeting, REQUIRED_ID)));
        when(slotRepository.findFreeSlotsCovering(ORGANIZER_ID, T0, T1)).thenReturn(List.of(organizerSlot1));
        when(slotRepository.findFreeSlotsCovering(REQUIRED_ID, T0, T1)).thenReturn(List.of());
        when(slotRepository.findFreeSlotsCovering(OPTIONAL_ID, T0, T1)).thenReturn(List.of());
        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Meeting result = meetingService.vote(MEETING_ID, REQUIRED_ID, Vote.YES);

        assertThat(result.getStatus()).isEqualTo(MeetingStatus.CONFIRMED);
        assertThat(result.getSlots()).isEmpty();
        assertThat(organizerSlot1.isFree()).isTrue();
    }

    // ── availability ──────────────────────────────────────────────────────────

    @Test
    void availability_throwsBadRequest_whenFromNotBeforeTo() {
        assertThrown(InvalidInputException.class,
                () -> meetingService.availability(List.of(ORGANIZER_ID), T1, T0));
    }

    @Test
    void availability_throwsBadRequest_whenFromNotGridAligned() {
        assertThrown(InvalidInputException.class,
                () -> meetingService.availability(List.of(ORGANIZER_ID), T0.plusSeconds(1), T1));
    }

    @Test
    void availability_throwsBadRequest_whenToNotGridAligned() {
        assertThrown(InvalidInputException.class,
                () -> meetingService.availability(List.of(ORGANIZER_ID), T0, T0.plusSeconds(600)));
    }

    @Test
    void availability_throwsBadRequest_whenRangeExceedsMaxWindows() {
        // MAX_AVAILABILITY_WINDOWS is 2_000; 2_001 windows on the 30-minute grid overflows it.
        Instant tooFar = T0.plusSeconds(2_001 * 1800L);
        assertThrown(InvalidInputException.class,
                () -> meetingService.availability(List.of(ORGANIZER_ID), T0, tooFar));
    }

    @Test
    void availability_throwsNotFound_whenUserMissing() {
        when(userRepository.existsById(ORGANIZER_ID)).thenReturn(false);
        assertThrown(NotFoundException.class,
                () -> meetingService.availability(List.of(ORGANIZER_ID), T0, T1));
    }

    @Test
    void availability_returnsWindows_taggedByWhichUsersAreFree() {
        when(userRepository.existsById(ORGANIZER_ID)).thenReturn(true);
        when(userRepository.existsById(REQUIRED_ID)).thenReturn(true);
        when(userRepository.existsById(OPTIONAL_ID)).thenReturn(true);
        // Organizer free the whole window; required free only the first half; optional never free.
        when(slotRepository.findFreeSlotsOverlapping(ORGANIZER_ID, T0, T1))
                .thenReturn(List.of(slotOwnedBy(ORGANIZER_ID, T0, MID), slotOwnedBy(ORGANIZER_ID, MID, T1)));
        when(slotRepository.findFreeSlotsOverlapping(REQUIRED_ID, T0, T1))
                .thenReturn(List.of(slotOwnedBy(REQUIRED_ID, T0, MID)));
        when(slotRepository.findFreeSlotsOverlapping(OPTIONAL_ID, T0, T1)).thenReturn(List.of());

        List<AvailabilityWindow> result =
                meetingService.availability(List.of(ORGANIZER_ID, REQUIRED_ID, OPTIONAL_ID), T0, T1);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).startTime()).isEqualTo(T0);
        assertThat(result.get(0).freeUserIds()).containsExactly(ORGANIZER_ID, REQUIRED_ID);
        assertThat(result.get(1).startTime()).isEqualTo(MID);
        assertThat(result.get(1).freeUserIds()).containsExactly(ORGANIZER_ID);
    }

    @Test
    void availability_returnsNoWindow_whenNoOneIsFree() {
        when(userRepository.existsById(ORGANIZER_ID)).thenReturn(true);
        when(slotRepository.findFreeSlotsOverlapping(ORGANIZER_ID, T0, T1)).thenReturn(List.of());

        List<AvailabilityWindow> result = meetingService.availability(List.of(ORGANIZER_ID), T0, T1);

        assertThat(result).isEmpty();
    }

    /**
     * Slot durations are client-chosen: one long FREE slot — even one extending beyond the
     * queried range on both sides — must tag its owner free for every window it contains.
     */
    @Test
    void availability_countsUserFree_whenOneLongSlotSpansWindows() {
        when(userRepository.existsById(ORGANIZER_ID)).thenReturn(true);
        when(slotRepository.findFreeSlotsOverlapping(ORGANIZER_ID, T0, T1))
                .thenReturn(List.of(slotOwnedBy(ORGANIZER_ID, T0.minusSeconds(1800), T1.plusSeconds(1800))));

        List<AvailabilityWindow> result = meetingService.availability(List.of(ORGANIZER_ID), T0, T1);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).startTime()).isEqualTo(T0);
        assertThat(result.get(1).startTime()).isEqualTo(MID);
    }

    @Test
    void availability_dedupesRepeatedUserIds() {
        when(userRepository.existsById(ORGANIZER_ID)).thenReturn(true);
        when(slotRepository.findFreeSlotsOverlapping(ORGANIZER_ID, T0, T1))
                .thenReturn(List.of(slotOwnedBy(ORGANIZER_ID, T0, MID)));

        List<AvailabilityWindow> result =
                meetingService.availability(List.of(ORGANIZER_ID, ORGANIZER_ID), T0, T1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).freeUserIds()).containsExactly(ORGANIZER_ID);
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void cancel_throwsNotFound_whenMeetingMissing() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.empty());
        assertThrown(NotFoundException.class, () -> meetingService.cancel(MEETING_ID, ORGANIZER_ID));
    }

    @Test
    void cancel_throwsForbidden_whenCallerNotOrganizer() {
        Meeting meeting = proposedMeeting();
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(MEETING_ID, REQUIRED_ID))
                .thenReturn(Optional.of(participantFor(meeting, REQUIRED_ID)));

        assertThrown(ForbiddenException.class, () -> meetingService.cancel(MEETING_ID, REQUIRED_ID));
    }

    @Test
    void cancel_throwsForbidden_whenCallerNotAParticipant() {
        Meeting meeting = proposedMeeting();
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(MEETING_ID, 999L)).thenReturn(Optional.empty());

        assertThrown(ForbiddenException.class, () -> meetingService.cancel(MEETING_ID, 999L));
    }

    @Test
    void cancel_throwsConflict_whenAlreadyCancelled() {
        Meeting meeting = proposedMeeting();
        meeting.cancel();
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(MEETING_ID, ORGANIZER_ID))
                .thenReturn(Optional.of(participantFor(meeting, ORGANIZER_ID)));

        assertThrown(ConflictException.class, () -> meetingService.cancel(MEETING_ID, ORGANIZER_ID));
    }

    @Test
    void cancel_confirmedMeeting_releasesSlotsAndCancels() {
        Meeting meeting = proposedMeeting();
        Slot slot = slotOwnedBy(REQUIRED_ID, T0, T1);
        meeting.confirm();
        meeting.addSlot(slot);

        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(MEETING_ID, ORGANIZER_ID))
                .thenReturn(Optional.of(participantFor(meeting, ORGANIZER_ID)));
        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        meetingService.cancel(MEETING_ID, ORGANIZER_ID);

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.CANCELLED);
        assertThat(slot.isFree()).isTrue();
        assertThat(meeting.getSlots()).isEmpty();
    }

    @Test
    void cancel_proposedMeeting_justCancels_noSlotsToRelease() {
        Meeting meeting = proposedMeeting();
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(meetingParticipantRepository.findByMeetingIdAndUserId(MEETING_ID, ORGANIZER_ID))
                .thenReturn(Optional.of(participantFor(meeting, ORGANIZER_ID)));
        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        meetingService.cancel(MEETING_ID, ORGANIZER_ID);

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.CANCELLED);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Meeting proposedMeeting() {
        Meeting meeting = Meeting.builder().title("Team sync").description("D").startTime(T0).endTime(T1).build();
        ReflectionTestUtils.setField(meeting, "id", MEETING_ID);
        meeting.addParticipant(new MeetingParticipant(meeting, organizer, ParticipantRole.ORGANIZER));
        meeting.addParticipant(new MeetingParticipant(meeting, required, ParticipantRole.REQUIRED));
        meeting.addParticipant(new MeetingParticipant(meeting, optional, ParticipantRole.OPTIONAL));
        return meeting;
    }

    private MeetingParticipant participantFor(Meeting meeting, Long userId) {
        return meeting.getParticipants().stream()
                .filter(p -> p.getUser().getId().equals(userId))
                .findFirst().orElseThrow();
    }

    private Slot slotOwnedBy(Long userId, Instant start, Instant end) {
        User owner = userWithId(userId, "User " + userId);
        Calendar cal = new Calendar(owner);
        Slot slot = new Slot(start, end);
        cal.addSlot(slot);
        return slot;
    }

    private User userWithId(Long id, String name) {
        User user = new User(name, name.toLowerCase() + "@test.com");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private MeetingCreateRequest request(Instant start, Instant end) {
        return new MeetingCreateRequest("Team sync", "Weekly sync", ORGANIZER_ID, start, end,
                List.of(REQUIRED_ID), List.of(OPTIONAL_ID));
    }

    private void assertThrown(Class<? extends DomainException> expected, ThrowingRunnable action) {
        assertThatThrownBy(action::run).isInstanceOf(expected);
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run();
    }
}
