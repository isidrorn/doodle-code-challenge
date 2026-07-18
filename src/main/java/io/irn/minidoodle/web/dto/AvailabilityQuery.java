package io.irn.minidoodle.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * Body for {@code QUERY /api/meetings/availability}: "when, within [from, to), is at least one of
 * these users free?" — the one piece of core Doodle behavior (suggest a time that works, instead of
 * requiring the caller to already know one) this API didn't otherwise expose. Unlike
 * {@link SlotQueryFilter}, every field here is required — there's no sensible "empty body" default
 * for "find me availability" the way an absent filter sensibly means "no filter, list everything"
 * for slots. So, unlike SlotQueryFilter, this DTO goes through the normal
 * {@code RequestValidator.parseAndValidate} path, not {@code SlotHandler.parseFilter}'s
 * catch-a-parse-failure-as-empty convention.
 */
public record AvailabilityQuery(
        @NotEmpty List<Long> userIds,
        @NotNull Instant from,
        @NotNull Instant to
) {}
