package io.irn.minidoodle.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A Meeting is proposed against N participants (via MeetingParticipant), then confirmed once
 * every REQUIRED participant votes YES. Confirming books whichever participants have full FREE
 * slot coverage for [startTime, endTime] — see MeetingService.confirm.
 */
@Entity
@Table(name = "meeting")
@Getter
@NoArgsConstructor
public class Meeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String description;
    private Instant startTime;
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    private MeetingStatus status;

    @ManyToMany
    @JoinTable(
            name = "slot_meeting",
            joinColumns = @JoinColumn(name = "meeting_id"),
            inverseJoinColumns = @JoinColumn(name = "slot_id"))
    private final List<Slot> slots = new ArrayList<>();

    @OneToMany(mappedBy = "meeting", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<MeetingParticipant> participants = new ArrayList<>();

    @Builder
    public Meeting(String title, String description, Instant startTime, Instant endTime) {
        this.title = title;
        this.description = description;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = MeetingStatus.PROPOSED;
    }

    public void addParticipant(MeetingParticipant participant) {
        participants.add(participant);
    }

    public void addSlot(Slot slot) {
        slots.add(slot);
        slot.getMeetings().add(this);
        slot.markBusy();
    }

    /** Frees and disassociates every booked slot — used when cancelling a CONFIRMED meeting. */
    public void releaseSlots() {
        for (Slot slot : slots) {
            slot.getMeetings().remove(this);
            slot.markFree();
        }
        slots.clear();
    }

    public void confirm() {
        this.status = MeetingStatus.CONFIRMED;
    }

    public void cancel() {
        this.status = MeetingStatus.CANCELLED;
    }

    public boolean isProposed() {
        return MeetingStatus.PROPOSED == status;
    }

    public boolean isConfirmed() {
        return MeetingStatus.CONFIRMED == status;
    }
}
