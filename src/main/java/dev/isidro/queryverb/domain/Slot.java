package dev.isidro.queryverb.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "slot", indexes = {
        @Index(name = "idx_slot_calendar_start", columnList = "calendar_id, start_time")
})
@Getter
@NoArgsConstructor
public class Slot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "calendar_id", nullable = false)
    private Calendar calendar;

    /**
     * Inverse side of Meeting.slots (@ManyToMany, join table slot_meeting). A slot can belong to
     * several PROPOSED meetings at once, but at most one CONFIRMED one — that constraint isn't
     * enforced at the DB level, MeetingService checks it when confirming.
     */
    @ManyToMany(mappedBy = "slots")
    private final List<Meeting> meetings = new ArrayList<>();

    private Instant startTime;
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    private SlotStatus status;

    /**
     * Optimistic locking: concurrent attempts to book the same slot will result in
     * an OptimisticLockException on the second writer → mapped to 409 Conflict.
     */
    @Version
    private Long version;

    public Slot(Instant startTime, Instant endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = SlotStatus.FREE;
    }

    /**
     * Assigns the calendar directly instead of going through Calendar.addSlot(),
     * so callers don't need to load the calendar's full slots collection just to
     * append one row — see SlotService.create().
     */
    public Slot(Calendar calendar, Instant startTime, Instant endTime) {
        this.calendar = calendar;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = SlotStatus.FREE;
    }

    public void markBusy() {
        this.status = SlotStatus.BUSY;
    }

    public void markFree() {
        this.status = SlotStatus.FREE;
    }

    public void reschedule(Instant newStart, Instant newEnd) {
        this.startTime = newStart;
        this.endTime = newEnd;
    }

    public boolean isFree() {
        return SlotStatus.FREE == this.status;
    }

    public boolean hasConfirmedMeeting() {
        return meetings.stream().anyMatch(Meeting::isConfirmed);
    }

    void assignCalendar(Calendar calendar) {
        this.calendar = calendar;
    }
}
