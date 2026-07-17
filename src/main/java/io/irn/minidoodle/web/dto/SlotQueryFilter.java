package io.irn.minidoodle.web.dto;

import io.irn.minidoodle.domain.SlotStatus;
import java.time.Instant;

/**
 * Filter payload sent in the body of an HTTP QUERY request.
 * All fields optional: absent means "no filter on this dimension".
 */
public record SlotQueryFilter(
        SlotStatus status,
        Instant from,
        Instant to
) {
    public static SlotQueryFilter empty() {
        return new SlotQueryFilter(null, null, null);
    }
}
