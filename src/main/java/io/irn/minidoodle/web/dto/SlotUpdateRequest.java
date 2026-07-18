package io.irn.minidoodle.web.dto;

import io.irn.minidoodle.domain.SlotStatus;
import java.time.Instant;

/**
 * PATCH payload: all fields optional — only non-null fields are applied.
 * <ul>
 *   <li>{@code startTime} alone shifts the slot, preserving its current length.</li>
 *   <li>{@code endTime} alone grows/shrinks the slot in place.</li>
 *   <li>Both together define a completely new interval.</li>
 * </ul>
 * Any supplied boundary must sit on the configured time grid. A status-only PATCH performs no
 * time validation at all — existing slots stay editable even if the grid was tightened after they
 * were created (the grid gates new boundaries, it never re-judges stored ones).
 */
public record SlotUpdateRequest(
        Instant startTime,
        Instant endTime,
        SlotStatus status
) {}
