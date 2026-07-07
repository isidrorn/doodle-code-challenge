package dev.isidro.queryverb.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
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

    @ManyToOne
    @JoinColumn(name = "meeting_id")
    private Meeting meeting;

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

    public void markBusy() {
        this.status = SlotStatus.BUSY;
    }

    public void markFree() {
        this.status = SlotStatus.FREE;
        this.meeting = null;
    }

    public void reschedule(Instant newStart, Instant newEnd) {
        this.startTime = newStart;
        this.endTime = newEnd;
    }

    public boolean isFree() {
        return SlotStatus.FREE == this.status;
    }

    void assignCalendar(Calendar calendar) {
        this.calendar = calendar;
    }

    void assignMeeting(Meeting meeting) {
        this.meeting = meeting;
        this.status = SlotStatus.BUSY;
    }
}
