package io.irn.minidoodle.web.mapper;

import io.irn.minidoodle.domain.Meeting;
import io.irn.minidoodle.domain.Slot;
import io.irn.minidoodle.web.dto.SlotResponse;
import org.springframework.stereotype.Component;

@Component
public class SlotMapper {

    public SlotResponse toResponse(Slot slot) {
        return new SlotResponse(
                slot.getId(),
                slot.getStartTime(),
                slot.getEndTime(),
                slot.getStatus(),
                slot.getMeetings().stream().map(Meeting::getId).toList()
        );
    }
}
