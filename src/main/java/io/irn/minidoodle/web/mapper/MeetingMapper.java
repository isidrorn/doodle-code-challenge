package io.irn.minidoodle.web.mapper;

import io.irn.minidoodle.domain.Meeting;
import io.irn.minidoodle.domain.MeetingParticipant;
import io.irn.minidoodle.web.dto.MeetingResponse;
import io.irn.minidoodle.web.dto.ParticipantResponse;
import org.springframework.stereotype.Component;

@Component
public class MeetingMapper {

    public MeetingResponse toResponse(Meeting meeting) {
        return new MeetingResponse(
                meeting.getId(),
                meeting.getTitle(),
                meeting.getDescription(),
                meeting.getStartTime(),
                meeting.getEndTime(),
                meeting.getStatus(),
                meeting.getParticipants().stream().map(this::toParticipantResponse).toList()
        );
    }

    private ParticipantResponse toParticipantResponse(MeetingParticipant participant) {
        return new ParticipantResponse(
                participant.getUser().getId(),
                participant.getUser().getName(),
                participant.getRole(),
                participant.getVote()
        );
    }
}
