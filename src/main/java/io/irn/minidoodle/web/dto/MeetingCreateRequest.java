package io.irn.minidoodle.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Creates a meeting in PROPOSED status with no slots booked yet — booking only happens once
 * every REQUIRED participant votes YES, see MeetingService.vote/confirm. Size caps match the DB
 * column sizes exactly (V3 migration).
 */
public record MeetingCreateRequest(
        @NotBlank @Size(max = 150) String title,
        @Size(max = 1000) String description,
        @NotNull Long organizerUserId,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        List<Long> requiredParticipantUserIds,
        List<Long> optionalParticipantUserIds
) {
    public MeetingCreateRequest {
        requiredParticipantUserIds = requiredParticipantUserIds == null ? List.of() : requiredParticipantUserIds;
        optionalParticipantUserIds = optionalParticipantUserIds == null ? List.of() : optionalParticipantUserIds;
    }
}
