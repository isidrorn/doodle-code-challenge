package dev.isidro.queryverb.web.dto;

import java.util.List;

public record MeetingResponse(
        Long id,
        String title,
        String description,
        List<SlotResponse> slots
) {}
