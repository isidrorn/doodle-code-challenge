package io.irn.minidoodle.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Size caps match the DB column sizes exactly (V3 migration) — an over-long value is a 400 at the
 * boundary, never a 500 out of the database.
 */
public record UserCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 254) String email
) {}
