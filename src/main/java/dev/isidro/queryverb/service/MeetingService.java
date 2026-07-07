package dev.isidro.queryverb.service;

import dev.isidro.queryverb.domain.Meeting;
import dev.isidro.queryverb.domain.Slot;
import dev.isidro.queryverb.repository.MeetingRepository;
import dev.isidro.queryverb.repository.SlotRepository;
import dev.isidro.queryverb.web.dto.MeetingCreateRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class MeetingService {

    private final SlotRepository slotRepository;
    private final MeetingRepository meetingRepository;

    /**
     * Converts an organizer's slot into a meeting and books all participant slots.
     *
     * <p>Concurrency strategy:
     * <ol>
     *   <li>All involved slots are acquired with PESSIMISTIC_WRITE to prevent
     *       concurrent booking between the overlap-check and the status update.</li>
     *   <li>@Version on Slot provides a second line of defence (optimistic locking)
     *       if the pessimistic lock is not available in a given deployment config.</li>
     * </ol>
     */
    public Meeting schedule(Long organizerUserId, Long organizerSlotId, MeetingCreateRequest request) {
        // Lock and validate organizer's slot
        Slot organizerSlot = slotRepository.findByIdForUpdate(organizerSlotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Slot not found: " + organizerSlotId));

        if (!organizerSlot.getCalendar().getOwner().getId().equals(organizerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Slot does not belong to userId=" + organizerUserId);
        }

        if (!organizerSlot.isFree()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Organizer slot %d is not FREE".formatted(organizerSlotId));
        }

        // Lock and validate participant slots
        List<Slot> allSlots = new ArrayList<>();
        allSlots.add(organizerSlot);

        for (Long participantSlotId : request.participantSlotIds()) {
            Slot participantSlot = slotRepository.findByIdForUpdate(participantSlotId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Participant slot not found: " + participantSlotId));

            if (!participantSlot.isFree()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Participant slot %d is not FREE".formatted(participantSlotId));
            }

            boolean disjoint = !participantSlot.getStartTime().isBefore(organizerSlot.getEndTime())
                    || !organizerSlot.getStartTime().isBefore(participantSlot.getEndTime());
            if (disjoint) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Participant slot %d does not overlap with organizer slot".formatted(participantSlotId));
            }

            allSlots.add(participantSlot);
        }

        // Create meeting and link all slots atomically
        Meeting meeting = Meeting.builder()
                .title(request.title())
                .description(request.description())
                .build();

        meeting = meetingRepository.save(meeting);

        for (Slot slot : allSlots) {
            meeting.addSlot(slot);
        }

        return meeting;
    }

    @Transactional(readOnly = true)
    public Meeting findById(Long meetingId) {
        return meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found: " + meetingId));
    }

    public void cancel(Long organizerUserId, Long slotId) {
        Slot slot = slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found: " + slotId));

        if (!slot.getCalendar().getOwner().getId().equals(organizerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Slot does not belong to userId=" + organizerUserId);
        }

        Meeting meeting = slot.getMeeting();
        if (meeting == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slot %d has no associated meeting".formatted(slotId));
        }

        meeting.getSlots().forEach(Slot::markFree);
        meetingRepository.delete(meeting);
    }
}
