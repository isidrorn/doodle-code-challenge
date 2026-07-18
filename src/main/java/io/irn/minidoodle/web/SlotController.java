package io.irn.minidoodle.web;

import io.irn.minidoodle.domain.SlotStatus;
import io.irn.minidoodle.service.SlotService;
import io.irn.minidoodle.web.dto.PageResponse;
import io.irn.minidoodle.web.dto.SlotBulkCreateRequest;
import io.irn.minidoodle.web.dto.SlotQueryFilter;
import io.irn.minidoodle.web.dto.SlotResponse;
import io.irn.minidoodle.web.dto.SlotUpdateRequest;
import io.irn.minidoodle.web.mapper.SlotMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/{userId}/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotService slotService;
    private final SlotMapper slotMapper;

    /**
     * Lists a user's slots, optionally filtered — every filter param is optional, and an absent
     * one means "no filter on that dimension". Paginated; out-of-range page/size is a 400, not
     * silently clamped.
     */
    @GetMapping
    public PageResponse<SlotResponse> list(
            @PathVariable Long userId,
            @RequestParam(required = false) SlotStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "startTime"));
        var result = slotService.query(userId, new SlotQueryFilter(status, from, to), pageable)
                .map(slotMapper::toResponse);
        return PageResponse.from(result);
    }

    @GetMapping("/{slotId}")
    public SlotResponse getOne(@PathVariable Long userId, @PathVariable Long slotId) {
        return slotMapper.toResponse(slotService.requireOwned(userId, slotId));
    }

    /** Bulk-creates every requested slot in one transaction; see SlotService.create. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<SlotResponse> create(@PathVariable Long userId,
                                     @Valid @RequestBody SlotBulkCreateRequest request) {
        return slotService.create(userId, request).stream().map(slotMapper::toResponse).toList();
    }

    // SlotUpdateRequest has no bean-validation constraints (every field is optional), so no
    // @Valid here — grid-alignment/overlap checks are business-rule validation in SlotService.
    @PatchMapping("/{slotId}")
    public SlotResponse update(@PathVariable Long userId, @PathVariable Long slotId,
                               @RequestBody SlotUpdateRequest request) {
        return slotMapper.toResponse(slotService.update(userId, slotId, request));
    }

    @DeleteMapping("/{slotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long userId, @PathVariable Long slotId) {
        slotService.delete(userId, slotId);
    }
}
