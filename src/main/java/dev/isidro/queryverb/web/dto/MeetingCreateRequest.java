package dev.isidro.queryverb.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Converts an organizer's slot into a meeting.
 *
 * <p>participantSlotIds: the slot IDs that each participant is willing to use
 * for this meeting. Each slot must be FREE and must overlap with the organizer's
 * slot. Verified and locked atomically in MeetingService.
 */
public record MeetingCreateRequest(
        @NotBlank String title,
        String description,
        @NotEmpty List<Long> participantSlotIds
) {}
