package io.irn.minidoodle.service;

import io.irn.minidoodle.config.TimeGridConfig;
import io.irn.minidoodle.domain.Meeting;
import io.irn.minidoodle.domain.MeetingParticipant;
import io.irn.minidoodle.domain.MeetingStatus;
import io.irn.minidoodle.domain.ParticipantRole;
import io.irn.minidoodle.domain.Slot;
import io.irn.minidoodle.domain.User;
import io.irn.minidoodle.domain.Vote;
import io.irn.minidoodle.exception.ConflictException;
import io.irn.minidoodle.exception.ForbiddenException;
import io.irn.minidoodle.exception.InvalidInputException;
import io.irn.minidoodle.exception.NotFoundException;
import io.irn.minidoodle.repository.MeetingParticipantRepository;
import io.irn.minidoodle.repository.MeetingRepository;
import io.irn.minidoodle.repository.SlotRepository;
import io.irn.minidoodle.repository.UserRepository;
import io.irn.minidoodle.web.dto.AvailabilityWindow;
import io.irn.minidoodle.web.dto.MeetingCreateRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MeetingService {

    /**
     * Caps how many grid windows a single availability query can walk — this loop is
     * O((to - from) / slotDuration), and an unbounded caller-supplied range would otherwise let
     * one request drive an arbitrarily large amount of server-side work. Mirrors the pagination
     * size cap: reject out-of-range rather than silently truncate.
     */
    private static final long MAX_AVAILABILITY_WINDOWS = 2_000;

    private final SlotRepository slotRepository;
    private final MeetingRepository meetingRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final UserRepository userRepository;
    private final TimeGridConfig timeGrid;

    /**
     * Creates a meeting in PROPOSED status. No slot is searched or locked here — that only
     * happens once every REQUIRED participant votes YES, see {@link #vote}.
     */
    public Meeting create(MeetingCreateRequest request) {
        Instant start = request.startTime();
        Instant end = request.endTime();

        if (!start.isBefore(end)) {
            throw new InvalidInputException("startTime must be before endTime");
        }
        if (!timeGrid.isAligned(start)) {
            throw new InvalidInputException("startTime %s is not aligned to the %d-minute time grid".formatted(start, timeGrid.timeGridMinutes()));
        }
        if (!timeGrid.isAligned(end)) {
            throw new InvalidInputException("endTime %s is not aligned to the %d-minute time grid".formatted(end, timeGrid.timeGridMinutes()));
        }

        User organizer = userRepository.findById(request.organizerUserId())
                .orElseThrow(() -> new NotFoundException("User not found: " + request.organizerUserId()));

        // A user listed more than once (e.g. also in required/optional, or repeated across both)
        // would otherwise violate MeetingParticipant's (meeting_id, user_id) unique constraint.
        Set<Long> addedUserIds = new LinkedHashSet<>();
        addedUserIds.add(organizer.getId());

        List<User> requiredUsers = loadUsers(request.requiredParticipantUserIds(), addedUserIds);
        List<User> optionalUsers = loadUsers(request.optionalParticipantUserIds(), addedUserIds);

        Meeting meeting = Meeting.builder()
                .title(request.title())
                .description(request.description())
                .startTime(start)
                .endTime(end)
                .build();

        meeting.addParticipant(new MeetingParticipant(meeting, organizer, ParticipantRole.ORGANIZER));
        requiredUsers.forEach(u -> meeting.addParticipant(new MeetingParticipant(meeting, u, ParticipantRole.REQUIRED)));
        optionalUsers.forEach(u -> meeting.addParticipant(new MeetingParticipant(meeting, u, ParticipantRole.OPTIONAL)));

        return meetingRepository.save(meeting);
    }

    private List<User> loadUsers(List<Long> userIds, Set<Long> addedUserIds) {
        List<User> users = new ArrayList<>();
        for (Long userId : userIds) {
            if (!addedUserIds.add(userId)) {
                continue;
            }
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new NotFoundException("User not found: " + userId));
            users.add(user);
        }
        return users;
    }

    /**
     * Finds every grid window in [from, to) where at least one of {@code userIds} is FREE —
     * the "suggest a time that works" query a real Doodle needs, that proposing a meeting alone
     * doesn't provide (proposing already requires the caller to have picked a startTime/endTime).
     * A user is free for a window when one of their FREE slots contains it entirely — slots have
     * client-chosen lengths, so this is interval containment against
     * {@link SlotRepository#findFreeSlotsOverlapping}, not a start-time set lookup. A window can
     * never straddle two slots: slot boundaries and windows both sit on the grid, and a window is
     * exactly one grid step wide.
     */
    @Transactional(readOnly = true)
    public List<AvailabilityWindow> availability(List<Long> userIds, Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new InvalidInputException("from must be before to");
        }
        if (!timeGrid.isAligned(from)) {
            throw new InvalidInputException("from %s is not aligned to the %d-minute time grid".formatted(from, timeGrid.timeGridMinutes()));
        }
        if (!timeGrid.isAligned(to)) {
            throw new InvalidInputException("to %s is not aligned to the %d-minute time grid".formatted(to, timeGrid.timeGridMinutes()));
        }

        long stepSeconds = timeGrid.stepSeconds();
        long totalWindows = Duration.between(from, to).toSeconds() / stepSeconds;
        if (totalWindows > MAX_AVAILABILITY_WINDOWS) {
            throw new InvalidInputException("requested range spans %d grid windows, exceeding the maximum of %d — narrow from/to"
                            .formatted(totalWindows, MAX_AVAILABILITY_WINDOWS));
        }

        Set<Long> distinctUserIds = new LinkedHashSet<>(userIds);
        for (Long userId : distinctUserIds) {
            if (!userRepository.existsById(userId)) {
                throw new NotFoundException("User not found: " + userId);
            }
        }

        Map<Long, List<Slot>> freeSlotsByUser = new HashMap<>();
        for (Long userId : distinctUserIds) {
            freeSlotsByUser.put(userId, slotRepository.findFreeSlotsOverlapping(userId, from, to));
        }

        List<AvailabilityWindow> windows = new ArrayList<>();
        for (Instant cursor = from; cursor.isBefore(to); cursor = cursor.plusSeconds(stepSeconds)) {
            Instant windowStart = cursor;
            Instant windowEnd = cursor.plusSeconds(stepSeconds);
            List<Long> freeUserIds = distinctUserIds.stream()
                    .filter(userId -> freeSlotsByUser.get(userId).stream()
                            .anyMatch(slot -> !slot.getStartTime().isAfter(windowStart)
                                    && !slot.getEndTime().isBefore(windowEnd)))
                    .toList();
            if (!freeUserIds.isEmpty()) {
                windows.add(new AvailabilityWindow(windowStart, windowEnd, freeUserIds));
            }
        }
        return windows;
    }

    @Transactional(readOnly = true)
    public Meeting findById(Long meetingId) {
        return meetingRepository.findById(meetingId)
                .orElseThrow(() -> new NotFoundException("Meeting not found: " + meetingId));
    }

    /**
     * Casts a participant's vote and applies the resulting state transition:
     * <ul>
     *   <li>A REQUIRED participant voting NO cancels the meeting immediately.</li>
     *   <li>Once every REQUIRED participant has voted YES, the meeting confirms — see
     *       {@link #confirm}.</li>
     *   <li>An OPTIONAL vote only records the vote; it never changes meeting status on its own
     *       (though it may be the vote that completes the "all REQUIRED are YES" condition if
     *       there happen to be no REQUIRED participants at all).</li>
     * </ul>
     */
    public Meeting vote(Long meetingId, Long userId, Vote vote) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new NotFoundException("Meeting not found: " + meetingId));

        userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        if (!meeting.isProposed()) {
            throw new ConflictException("Meeting %d is not PROPOSED".formatted(meetingId));
        }

        MeetingParticipant participant = meetingParticipantRepository.findByMeetingIdAndUserId(meetingId, userId)
                .orElseThrow(() -> new InvalidInputException("userId=%d is not a participant of meeting %d".formatted(userId, meetingId)));

        participant.castVote(vote);

        if (participant.isRequired() && vote == Vote.NO) {
            meeting.cancel();
            return meetingRepository.save(meeting);
        }

        boolean allRequiredYes = meeting.getParticipants().stream()
                .filter(MeetingParticipant::isRequired)
                .allMatch(p -> p.getVote() == Vote.YES);

        if (allRequiredYes) {
            confirm(meeting);
        }

        return meetingRepository.save(meeting);
    }

    /**
     * Books each participant's FREE slots covering the meeting window when a full, contiguous
     * cover exists. A participant without one (e.g. already BUSY from another confirmed meeting)
     * is skipped silently — reorganizing their own calendar is their responsibility, and it
     * doesn't block confirming the meeting for everyone else.
     */
    private void confirm(Meeting meeting) {
        meeting.confirm();
        for (MeetingParticipant participant : meeting.getParticipants()) {
            List<Slot> freeSlots = slotRepository.findFreeSlotsCovering(
                    participant.getUser().getId(), meeting.getStartTime(), meeting.getEndTime());
            if (fullyCovers(freeSlots, meeting.getStartTime(), meeting.getEndTime())) {
                freeSlots.forEach(meeting::addSlot);
            }
        }
    }

    /** True iff the (already start-ordered) slots form a gapless chain from start to end. */
    private boolean fullyCovers(List<Slot> slots, Instant start, Instant end) {
        if (slots.isEmpty()) {
            return false;
        }
        Instant cursor = start;
        for (Slot slot : slots) {
            if (!slot.getStartTime().equals(cursor)) {
                return false;
            }
            cursor = slot.getEndTime();
        }
        return cursor.equals(end);
    }

    public void cancel(Long meetingId, Long userId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new NotFoundException("Meeting not found: " + meetingId));

        MeetingParticipant caller = meetingParticipantRepository.findByMeetingIdAndUserId(meetingId, userId).orElse(null);
        if (caller == null || !caller.isOrganizer()) {
            throw new ForbiddenException("userId=%d is not the organizer of meeting %d".formatted(userId, meetingId));
        }

        if (meeting.getStatus() == MeetingStatus.CANCELLED) {
            throw new ConflictException("Meeting %d is already cancelled".formatted(meetingId));
        }

        if (meeting.isConfirmed()) {
            meeting.releaseSlots();
        }
        meeting.cancel();
        meetingRepository.save(meeting);
    }
}
