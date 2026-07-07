package dev.isidro.queryverb.web.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record SlotCreateRequest(
        @NotNull Instant startTime,
        @NotNull Instant endTime
) {}
