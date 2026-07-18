package io.irn.minidoodle.service;

import io.irn.minidoodle.config.TimeGridConfig;
import io.irn.minidoodle.domain.Calendar;
import io.irn.minidoodle.domain.Slot;
import io.irn.minidoodle.domain.SlotStatus;
import io.irn.minidoodle.exception.ConflictException;
import io.irn.minidoodle.exception.InvalidInputException;
import io.irn.minidoodle.exception.NotFoundException;
import io.irn.minidoodle.repository.CalendarRepository;
import io.irn.minidoodle.repository.SlotRepository;
import io.irn.minidoodle.web.dto.SlotBulkCreateRequest;
import io.irn.minidoodle.web.dto.SlotBulkCreateRequest.SlotCreateItem;
import io.irn.minidoodle.web.dto.SlotQueryFilter;
import io.irn.minidoodle.web.dto.SlotUpdateRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class SlotService {

    private final SlotRepository slotRepository;
    private final CalendarRepository calendarRepository;
    private final TimeGridConfig timeGrid;

    /**
     * Two queries, not one — see SlotRepository.searchIds's note on why pagination and an
     * eagerly-fetched @ManyToMany collection can't share a single query.
     */
    @Transactional(readOnly = true)
    public Page<Slot> query(Long userId, SlotQueryFilter filter, Pageable pageable) {
        Page<Long> idPage = slotRepository.searchIds(userId, filter.status(), filter.from(), filter.to(), pageable);
        List<Slot> slots = slotRepository.findByIdInWithMeetings(idPage.getContent());
        return new PageImpl<>(slots, pageable, idPage.getTotalElements());
    }

    /**
     * Creates every requested slot in one transaction — any invalid or conflicting interval fails
     * the whole batch (nothing is partially created), per the "elegir fallo en bloque" decision
     * in the design log. Each slot's [startTime, endTime) is client-chosen; the time grid only
     * validates the boundaries (see TimeGridConfig).
     */
    public List<Slot> create(Long userId, SlotBulkCreateRequest request) {
        for (SlotCreateItem item : request.slots()) {
            validateInterval(item);
        }

        // Two new, unsaved slots can't overlap-check against each other via a repository query —
        // catch in-request overlaps by sorting and comparing neighbours before any DB round-trip.
        List<SlotCreateItem> sorted = new ArrayList<>(request.slots());
        sorted.sort(Comparator.comparing(SlotCreateItem::startTime));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).startTime().isBefore(sorted.get(i - 1).endTime())) {
                throw new ConflictException("Requested slots overlap each other at %s".formatted(sorted.get(i).startTime()));
            }
        }

        Calendar calendar = calendarRepository.findByOwnerIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("Calendar not found for userId=" + userId));

        List<Slot> newSlots = new ArrayList<>();
        for (SlotCreateItem item : request.slots()) {
            if (slotRepository.existsOverlap(userId, item.startTime(), item.endTime(), null)) {
                throw new ConflictException("Slot [%s, %s) overlaps with an existing slot".formatted(item.startTime(), item.endTime()));
            }
            newSlots.add(new Slot(calendar, item.startTime(), item.endTime()));
        }

        return slotRepository.saveAll(newSlots);
    }

    public Slot update(Long userId, Long slotId, SlotUpdateRequest request) {
        Slot slot = requireOwned(userId, slotId);

        if (slot.hasConfirmedMeeting()) {
            throw new ConflictException("Cannot modify a slot booked in a confirmed meeting");
        }

        boolean timeChanged = request.startTime() != null || request.endTime() != null;
        if (timeChanged) {
            Instant newStart = request.startTime() != null ? request.startTime() : slot.getStartTime();
            // startTime alone shifts the slot preserving its length; endTime alone resizes in place.
            Instant newEnd = request.endTime() != null
                    ? request.endTime()
                    : newStart.plus(Duration.between(slot.getStartTime(), slot.getEndTime()));

            validateInterval(new SlotCreateItem(newStart, newEnd));

            // Serializes the overlap-check-then-write sequence per user — see
            // CalendarRepository.findByOwnerIdForUpdate.
            calendarRepository.findByOwnerIdForUpdate(userId)
                    .orElseThrow(() -> new NotFoundException("Calendar not found for userId=" + userId));

            if (slotRepository.existsOverlap(userId, newStart, newEnd, slotId)) {
                throw new ConflictException("Updated slot would overlap with an existing slot");
            }

            slot.reschedule(newStart, newEnd);
        }

        if (request.status() == SlotStatus.FREE) slot.markFree();
        else if (request.status() == SlotStatus.BUSY) slot.markBusy();

        return slotRepository.save(slot);
    }

    public void delete(Long userId, Long slotId) {
        Slot slot = requireOwned(userId, slotId);
        if (slot.hasConfirmedMeeting()) {
            throw new ConflictException("Cannot delete a slot booked in a confirmed meeting");
        }
        slotRepository.delete(slot);
    }

    @Transactional(readOnly = true)
    public Slot requireOwned(Long userId, Long slotId) {
        return slotRepository.findByUserIdAndSlotId(userId, slotId)
                .orElseThrow(() -> new NotFoundException("Slot %d not found for userId=%d".formatted(slotId, userId)));
    }

    private void validateInterval(SlotCreateItem item) {
        if (item == null || item.startTime() == null || item.endTime() == null) {
            throw new InvalidInputException("Each slot needs both startTime and endTime");
        }
        if (!timeGrid.isAligned(item.startTime())) {
            throw new InvalidInputException("startTime %s is not aligned to the %d-minute time grid"
                            .formatted(item.startTime(), timeGrid.timeGridMinutes()));
        }
        if (!timeGrid.isAligned(item.endTime())) {
            throw new InvalidInputException("endTime %s is not aligned to the %d-minute time grid"
                            .formatted(item.endTime(), timeGrid.timeGridMinutes()));
        }
        if (!item.startTime().isBefore(item.endTime())) {
            throw new InvalidInputException("startTime must be before endTime");
        }
    }
}
