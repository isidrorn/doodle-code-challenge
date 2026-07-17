package dev.isidro.queryverb.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "meeting_participant",
       uniqueConstraints = @UniqueConstraint(columnNames = {"meeting_id", "user_id"}))
@Getter
@NoArgsConstructor
public class MeetingParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    private ParticipantRole role;

    @Enumerated(EnumType.STRING)
    private Vote vote;

    public MeetingParticipant(Meeting meeting, User user, ParticipantRole role) {
        this.meeting = meeting;
        this.user = user;
        this.role = role;
        this.vote = role == ParticipantRole.ORGANIZER ? Vote.YES : Vote.PENDING;
    }

    public void castVote(Vote vote) {
        this.vote = vote;
    }

    public boolean isRequired() {
        return ParticipantRole.REQUIRED == role;
    }

    public boolean isOrganizer() {
        return ParticipantRole.ORGANIZER == role;
    }
}
