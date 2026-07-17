package dev.isidro.queryverb.web.mapper;

import dev.isidro.queryverb.domain.Meeting;
import dev.isidro.queryverb.domain.MeetingParticipant;
import dev.isidro.queryverb.web.dto.MeetingResponse;
import dev.isidro.queryverb.web.dto.ParticipantResponse;
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
