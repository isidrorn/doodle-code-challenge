package io.irn.minidoodle.web.dto;

import io.irn.minidoodle.domain.SlotStatus;
import java.time.Instant;
import java.util.List;

public record SlotResponse(
        Long id,
        Instant startTime,
        Instant endTime,
        SlotStatus status,
        List<Long> meetingIds
) {}
