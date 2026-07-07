package dev.isidro.queryverb.web.dto;

import dev.isidro.queryverb.domain.SlotStatus;
import java.time.Instant;

/**
 * PATCH payload: all fields optional — only non-null fields are applied.
 */
public record SlotUpdateRequest(
        Instant startTime,
        Instant endTime,
        SlotStatus status
) {}
