package dev.isidro.queryverb.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A Meeting aggregates N Slots, one per participant's calendar.
 * Relationship: Meeting 1—N Slot (each Slot carries a nullable FK to Meeting).
 *
 * <p>Deriving participants from slots (slot → calendar → user) avoids a redundant
 * @ManyToMany and keeps the model consistent: if a slot is removed, the participant
 * link disappears automatically.
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

    @OneToMany(mappedBy = "meeting")
    private final List<Slot> slots = new ArrayList<>();

    @Builder
    public Meeting(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public void addSlot(Slot slot) {
        slots.add(slot);
        slot.assignMeeting(this);
    }
}
