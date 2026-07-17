package io.irn.minidoodle.repository;

import io.irn.minidoodle.domain.MeetingParticipant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, Long> {

    Optional<MeetingParticipant> findByMeetingIdAndUserId(Long meetingId, Long userId);

    List<MeetingParticipant> findByMeetingId(Long meetingId);
}
