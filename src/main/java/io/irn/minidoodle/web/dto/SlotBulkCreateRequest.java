package io.irn.minidoodle.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * Each requested slot carries its own client-chosen {@code [startTime, endTime)} — slots have no
 * fixed system duration. Both boundaries must sit on the configured time grid
 * ({@code scheduling.time-grid-minutes}); the grid validates boundaries, it never derives them.
 * See design-decisions-v7.md.
 */
public record SlotBulkCreateRequest(
        @NotEmpty List<@Valid SlotCreateItem> slots
) {
    public record SlotCreateItem(
            @NotNull Instant startTime,
            @NotNull Instant endTime
    ) {}
}
