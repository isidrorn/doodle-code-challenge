package io.irn.minidoodle.web.dto;

import io.irn.minidoodle.domain.Vote;
import jakarta.validation.constraints.NotNull;

public record VoteRequest(
        @NotNull Vote vote
) {}
