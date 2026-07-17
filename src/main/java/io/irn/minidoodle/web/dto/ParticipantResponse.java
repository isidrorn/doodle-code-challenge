package io.irn.minidoodle.web.dto;

import io.irn.minidoodle.domain.ParticipantRole;
import io.irn.minidoodle.domain.Vote;

public record ParticipantResponse(
        Long userId,
        String name,
        ParticipantRole role,
        Vote vote
) {}
