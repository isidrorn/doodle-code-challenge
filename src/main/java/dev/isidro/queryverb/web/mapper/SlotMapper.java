package dev.isidro.queryverb.web.mapper;

import dev.isidro.queryverb.domain.Meeting;
import dev.isidro.queryverb.domain.Slot;
import dev.isidro.queryverb.web.dto.SlotResponse;
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
