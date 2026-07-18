package io.irn.minidoodle.web.dto;

import io.irn.minidoodle.domain.SlotStatus;
import java.time.Instant;

/**
 * Optional filter criteria for listing a user's slots (GET /api/users/{userId}/slots).
 * All fields optional: null means "no filter on this dimension".
 */
public record SlotQueryFilter(
        SlotStatus status,
        Instant from,
        Instant to
) {}
