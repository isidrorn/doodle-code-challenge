package io.irn.minidoodle.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.irn.minidoodle.config.TimeGridConfig;
import io.irn.minidoodle.exception.ConflictException;
import io.irn.minidoodle.exception.DomainException;
import io.irn.minidoodle.exception.InvalidInputException;
import io.irn.minidoodle.exception.NotFoundException;
import io.irn.minidoodle.domain.Calendar;
import io.irn.minidoodle.domain.Meeting;
import io.irn.minidoodle.domain.Slot;
import io.irn.minidoodle.domain.SlotStatus;
import io.irn.minidoodle.domain.User;
import io.irn.minidoodle.repository.CalendarRepository;
import io.irn.minidoodle.repository.SlotRepository;
import io.irn.minidoodle.web.dto.SlotBulkCreateRequest;
import io.irn.minidoodle.web.dto.SlotBulkCreateRequest.SlotCreateItem;
import io.irn.minidoodle.web.dto.SlotUpdateRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SlotServiceTest {

    @Mock SlotRepository slotRepository;
    @Mock CalendarRepository calendarRepository;

    SlotService slotService;

    static final Long USER_ID = 1L;
    static final Long SLOT_ID = 10L;
    // 30-minute grid (default TimeGridConfig)
    static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");
    static final Instant T1 = Instant.parse("2026-06-01T09:30:00Z");
    static final Instant T2 = Instant.parse("2026-06-01T10:00:00Z");
    static final Instant T3 = Instant.parse("2026-06-01T10:30:00Z");

    @BeforeEach
    void setUp() {
        slotService = new SlotService(slotRepository, calendarRepository, new TimeGridConfig(30));
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_throwsBadRequest_whenStartTimeNotGridAligned() {
        assertThrown(InvalidInputException.class,
                () -> slotService.create(USER_ID, requestOf(new SlotCreateItem(T0.plusSeconds(1), T1))));
    }

    @Test
    void create_throwsBadRequest_whenEndTimeNotGridAligned() {
        assertThrown(InvalidInputException.class,
                () -> slotService.create(USER_ID, requestOf(new SlotCreateItem(T0, T1.plusSeconds(1)))));
    }

    @Test
    void create_throwsBadRequest_whenStartNotBeforeEnd() {
        assertThrown(InvalidInputException.class,
                () -> slotService.create(USER_ID, requestOf(new SlotCreateItem(T1, T0))));
    }

    @Test
    void create_throwsBadRequest_whenTimesMissing() {
        assertThrown(InvalidInputException.class,
                () -> slotService.create(USER_ID, requestOf(new SlotCreateItem(T0, null))));
    }

    @Test
    void create_throwsConflict_whenRequestedSlotsOverlapEachOther() {
        assertThrown(ConflictException.class,
                () -> slotService.create(USER_ID,
                        requestOf(new SlotCreateItem(T0, T2), new SlotCreateItem(T1, T3))));
    }

    @Test
    void create_throwsConflict_whenOverlapExists() {
        Calendar calendar = new Calendar(new User("Test", "test@test.com"));
        when(calendarRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(calendar));
        when(slotRepository.existsOverlap(USER_ID, T0, T1, null)).thenReturn(true);

        assertThrown(ConflictException.class,
                () -> slotService.create(USER_ID, requestOf(new SlotCreateItem(T0, T1))));
    }

    @Test
    void create_savesAllSlots_whenValid() {
        Calendar calendar = new Calendar(new User("Test", "test@test.com"));
        when(calendarRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(calendar));
        when(slotRepository.existsOverlap(eq(USER_ID), any(), any(), eq(null))).thenReturn(false);
        when(slotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Slot> result = slotService.create(USER_ID,
                requestOf(new SlotCreateItem(T0, T1), new SlotCreateItem(T1, T2)));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStartTime()).isEqualTo(T0);
        assertThat(result.get(0).getEndTime()).isEqualTo(T1);
        assertThat(result.get(1).getStartTime()).isEqualTo(T1);
        assertThat(result.get(1).getEndTime()).isEqualTo(T2);
        assertThat(result).allMatch(Slot::isFree);
    }

    /** Durations are client-chosen: a single slot can span any number of grid steps. */
    @Test
    void create_allowsVariableDurations() {
        Calendar calendar = new Calendar(new User("Test", "test@test.com"));
        when(calendarRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(calendar));
        when(slotRepository.existsOverlap(eq(USER_ID), any(), any(), eq(null))).thenReturn(false);
        when(slotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Slot> result = slotService.create(USER_ID,
                requestOf(new SlotCreateItem(T0, T2), new SlotCreateItem(T2, T3)));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStartTime()).isEqualTo(T0);
        assertThat(result.get(0).getEndTime()).isEqualTo(T2);
        assertThat(result.get(1).getEndTime()).isEqualTo(T3);
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_throwsConflict_whenSlotHasConfirmedMeeting() {
        Slot slot = slotWithConfirmedMeeting();
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));

        assertThrown(ConflictException.class,
                () -> slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(null, null, null)));
    }

    /**
     * A status-only PATCH performs no time validation or overlap check at all — existing slots
     * stay editable even if the grid parameter was tightened after they were created.
     */
    @Test
    void update_allowsModification_whenSlotOnlyInProposedMeeting() {
        Slot slot = slotInProposedMeeting();
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));
        when(slotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Slot result = slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(null, null, SlotStatus.BUSY));

        assertThat(result.getStatus()).isEqualTo(SlotStatus.BUSY);
    }

    @Test
    void update_throwsBadRequest_whenStartTimeNotGridAligned() {
        Slot slot = new Slot(T0, T1);
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));

        assertThrown(InvalidInputException.class,
                () -> slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(T0.plusSeconds(1), null, null)));
    }

    @Test
    void update_throwsBadRequest_whenEndTimeNotGridAligned() {
        Slot slot = new Slot(T0, T1);
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));

        assertThrown(InvalidInputException.class,
                () -> slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(null, T1.plusSeconds(1), null)));
    }

    @Test
    void update_throwsConflict_whenOverlapExists() {
        Slot slot = new Slot(T0, T1);
        Calendar calendar = new Calendar(new User("Test", "test@test.com"));
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));
        when(calendarRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(calendar));
        when(slotRepository.existsOverlap(eq(USER_ID), any(), any(), eq(SLOT_ID))).thenReturn(true);

        assertThrown(ConflictException.class,
                () -> slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(T2, null, null)));
    }

    /** startTime alone shifts the slot, preserving its current length. */
    @Test
    void update_shiftsSlotPreservingLength_whenOnlyStartTimeGiven() {
        Slot slot = new Slot(T0, T1);
        Calendar calendar = new Calendar(new User("Test", "test@test.com"));
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));
        when(calendarRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(calendar));
        when(slotRepository.existsOverlap(eq(USER_ID), any(), any(), eq(SLOT_ID))).thenReturn(false);
        when(slotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Slot result = slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(T1, null, SlotStatus.BUSY));

        assertThat(result.getStatus()).isEqualTo(SlotStatus.BUSY);
        assertThat(result.getStartTime()).isEqualTo(T1);
        assertThat(result.getEndTime()).isEqualTo(T2);
    }

    /** endTime alone grows/shrinks the slot in place. */
    @Test
    void update_resizesSlot_whenOnlyEndTimeGiven() {
        Slot slot = new Slot(T0, T1);
        Calendar calendar = new Calendar(new User("Test", "test@test.com"));
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));
        when(calendarRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(calendar));
        when(slotRepository.existsOverlap(eq(USER_ID), any(), any(), eq(SLOT_ID))).thenReturn(false);
        when(slotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Slot result = slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(null, T2, null));

        assertThat(result.getStartTime()).isEqualTo(T0);
        assertThat(result.getEndTime()).isEqualTo(T2);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_throwsConflict_whenSlotHasConfirmedMeeting() {
        Slot slot = slotWithConfirmedMeeting();
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));

        assertThrown(ConflictException.class, () -> slotService.delete(USER_ID, SLOT_ID));
    }

    @Test
    void delete_deletesSlot_whenFree() {
        Slot slot = new Slot(T0, T1);
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));

        slotService.delete(USER_ID, SLOT_ID);

        verify(slotRepository).delete(slot);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private SlotBulkCreateRequest requestOf(SlotCreateItem... items) {
        return new SlotBulkCreateRequest(List.of(items));
    }

    private Slot slotWithConfirmedMeeting() {
        Slot slot = new Slot(T0, T1);
        Meeting meeting = Meeting.builder().title("M").description("D").startTime(T0).endTime(T1).build();
        meeting.addSlot(slot);
        meeting.confirm();
        return slot;
    }

    private Slot slotInProposedMeeting() {
        Slot slot = new Slot(T0, T1);
        Meeting meeting = Meeting.builder().title("M").description("D").startTime(T0).endTime(T1).build();
        meeting.addSlot(slot);
        return slot;
    }

    private void assertThrown(Class<? extends DomainException> expected, ThrowingRunnable action) {
        assertThatThrownBy(action::run).isInstanceOf(expected);
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run();
    }
}
