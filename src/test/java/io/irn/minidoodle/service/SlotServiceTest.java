package io.irn.minidoodle.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.irn.minidoodle.config.SlotDurationConfig;
import io.irn.minidoodle.domain.Calendar;
import io.irn.minidoodle.domain.Meeting;
import io.irn.minidoodle.domain.Slot;
import io.irn.minidoodle.domain.SlotStatus;
import io.irn.minidoodle.domain.User;
import io.irn.minidoodle.repository.CalendarRepository;
import io.irn.minidoodle.repository.SlotRepository;
import io.irn.minidoodle.web.dto.SlotBulkCreateRequest;
import io.irn.minidoodle.web.dto.SlotUpdateRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SlotServiceTest {

    @Mock SlotRepository slotRepository;
    @Mock CalendarRepository calendarRepository;

    SlotService slotService;

    static final Long USER_ID = 1L;
    static final Long SLOT_ID = 10L;
    // 30-minute grid (default SlotDurationConfig)
    static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");
    static final Instant T1 = Instant.parse("2026-06-01T09:30:00Z");
    static final Instant T2 = Instant.parse("2026-06-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        slotService = new SlotService(slotRepository, calendarRepository, new SlotDurationConfig(30));
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_throwsBadRequest_whenStartTimeNotGridAligned() {
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> slotService.create(USER_ID, new SlotBulkCreateRequest(List.of(T0.plusSeconds(1)))));
    }

    @Test
    void create_throwsBadRequest_whenStartTimeIsNull() {
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> slotService.create(USER_ID, new SlotBulkCreateRequest(Arrays.asList(T0, null))));
    }

    @Test
    void create_throwsConflict_whenDuplicateStartTimeWithinRequest() {
        assertStatus(HttpStatus.CONFLICT,
                () -> slotService.create(USER_ID, new SlotBulkCreateRequest(List.of(T0, T0))));
    }

    @Test
    void create_throwsConflict_whenOverlapExists() {
        Calendar calendar = new Calendar(new User("Test", "test@test.com"));
        when(calendarRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(calendar));
        when(slotRepository.existsOverlap(USER_ID, T0, T1, null)).thenReturn(true);

        assertStatus(HttpStatus.CONFLICT,
                () -> slotService.create(USER_ID, new SlotBulkCreateRequest(List.of(T0))));
    }

    @Test
    void create_savesAllSlots_whenValid() {
        Calendar calendar = new Calendar(new User("Test", "test@test.com"));
        when(calendarRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(calendar));
        when(slotRepository.existsOverlap(eq(USER_ID), any(), any(), eq(null))).thenReturn(false);
        when(slotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Slot> result = slotService.create(USER_ID, new SlotBulkCreateRequest(List.of(T0, T1)));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStartTime()).isEqualTo(T0);
        assertThat(result.get(0).getEndTime()).isEqualTo(T1);
        assertThat(result.get(1).getStartTime()).isEqualTo(T1);
        assertThat(result.get(1).getEndTime()).isEqualTo(T2);
        assertThat(result).allMatch(Slot::isFree);
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_throwsConflict_whenSlotHasConfirmedMeeting() {
        Slot slot = slotWithConfirmedMeeting();
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));

        assertStatus(HttpStatus.CONFLICT,
                () -> slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(null, null)));
    }

    @Test
    void update_allowsModification_whenSlotOnlyInProposedMeeting() {
        Slot slot = slotInProposedMeeting();
        Calendar calendar = new Calendar(new User("Test", "test@test.com"));
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));
        when(calendarRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(calendar));
        when(slotRepository.existsOverlap(eq(USER_ID), any(), any(), eq(SLOT_ID))).thenReturn(false);
        when(slotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Slot result = slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(null, SlotStatus.BUSY));

        assertThat(result.getStatus()).isEqualTo(SlotStatus.BUSY);
    }

    @Test
    void update_throwsBadRequest_whenStartTimeNotGridAligned() {
        Slot slot = new Slot(T0, T1);
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));

        assertStatus(HttpStatus.BAD_REQUEST,
                () -> slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(T0.plusSeconds(1), null)));
    }

    @Test
    void update_throwsConflict_whenOverlapExists() {
        Slot slot = new Slot(T0, T1);
        Calendar calendar = new Calendar(new User("Test", "test@test.com"));
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));
        when(calendarRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(calendar));
        when(slotRepository.existsOverlap(eq(USER_ID), any(), any(), eq(SLOT_ID))).thenReturn(true);

        assertStatus(HttpStatus.CONFLICT,
                () -> slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(T2, null)));
    }

    @Test
    void update_marksSlotBusy_andRecomputesEndTime_whenRescheduled() {
        Slot slot = new Slot(T0, T1);
        Calendar calendar = new Calendar(new User("Test", "test@test.com"));
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));
        when(calendarRepository.findByOwnerIdForUpdate(USER_ID)).thenReturn(Optional.of(calendar));
        when(slotRepository.existsOverlap(eq(USER_ID), any(), any(), eq(SLOT_ID))).thenReturn(false);
        when(slotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Slot result = slotService.update(USER_ID, SLOT_ID, new SlotUpdateRequest(T1, SlotStatus.BUSY));

        assertThat(result.getStatus()).isEqualTo(SlotStatus.BUSY);
        assertThat(result.getStartTime()).isEqualTo(T1);
        assertThat(result.getEndTime()).isEqualTo(T2);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_throwsConflict_whenSlotHasConfirmedMeeting() {
        Slot slot = slotWithConfirmedMeeting();
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));

        assertStatus(HttpStatus.CONFLICT, () -> slotService.delete(USER_ID, SLOT_ID));
    }

    @Test
    void delete_deletesSlot_whenFree() {
        Slot slot = new Slot(T0, T1);
        when(slotRepository.findByUserIdAndSlotId(USER_ID, SLOT_ID)).thenReturn(Optional.of(slot));

        slotService.delete(USER_ID, SLOT_ID);

        verify(slotRepository).delete(slot);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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

    private void assertStatus(HttpStatus expected, ThrowingRunnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(expected);
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run();
    }
}
