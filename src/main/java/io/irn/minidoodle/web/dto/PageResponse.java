package io.irn.minidoodle.web.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Response envelope for paginated list endpoints. Wraps Spring Data's {@link Page} instead of
 * serializing it directly — {@code Page}'s own default JSON shape carries internal fields
 * ({@code pageable}, {@code sort}, etc.) that aren't meant as public API surface.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
