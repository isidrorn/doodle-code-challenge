package io.irn.minidoodle.web.dto;

import io.irn.minidoodle.domain.MeetingStatus;
import java.time.Instant;
import java.util.List;

public record MeetingResponse(
        Long id,
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        MeetingStatus status,
        List<ParticipantResponse> participants
) {}
