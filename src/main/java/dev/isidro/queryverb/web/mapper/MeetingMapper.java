package dev.isidro.queryverb.web.mapper;

import dev.isidro.queryverb.domain.Meeting;
import dev.isidro.queryverb.web.dto.MeetingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MeetingMapper {

    private final SlotMapper slotMapper;

    public MeetingResponse toResponse(Meeting meeting) {
        return new MeetingResponse(
                meeting.getId(),
                meeting.getTitle(),
                meeting.getDescription(),
                meeting.getSlots().stream().map(slotMapper::toResponse).toList()
        );
    }
}
