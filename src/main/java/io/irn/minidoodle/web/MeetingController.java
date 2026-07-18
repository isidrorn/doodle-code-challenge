package io.irn.minidoodle.web;

import io.irn.minidoodle.service.MeetingService;
import io.irn.minidoodle.web.dto.AvailabilityWindow;
import io.irn.minidoodle.web.dto.MeetingCancelRequest;
import io.irn.minidoodle.web.dto.MeetingCreateRequest;
import io.irn.minidoodle.web.dto.MeetingResponse;
import io.irn.minidoodle.web.dto.VoteRequest;
import io.irn.minidoodle.web.mapper.MeetingMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;
    private final MeetingMapper meetingMapper;

    /** Proposes a meeting (PROPOSED, no slots booked) — booking happens on confirmation, see vote. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingResponse create(@Valid @RequestBody MeetingCreateRequest request) {
        return meetingMapper.toResponse(meetingService.create(request));
    }

    /**
     * "When, within [from, to), is at least one of these users free?" — unlike the slot-list
     * filters, every parameter here is required: there's no sensible default set of users or range
     * for an availability search. (The literal {@code /availability} segment always beats the
     * {@code /{meetingId}} template in Spring's path matching, so this route can't be shadowed.)
     */
    @GetMapping("/availability")
    public List<AvailabilityWindow> availability(
            @RequestParam @NotEmpty List<Long> userIds,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        return meetingService.availability(userIds, from, to);
    }

    @GetMapping("/{meetingId}")
    public MeetingResponse getOne(@PathVariable Long meetingId) {
        return meetingMapper.toResponse(meetingService.findById(meetingId));
    }

    @PostMapping("/{meetingId}/participants/{userId}/vote")
    public MeetingResponse vote(@PathVariable Long meetingId, @PathVariable Long userId,
                                @Valid @RequestBody VoteRequest request) {
        return meetingMapper.toResponse(meetingService.vote(meetingId, userId, request.vote()));
    }

    /** Cancels a meeting; the body's userId must be the organizer (403 otherwise). */
    @DeleteMapping("/{meetingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long meetingId, @Valid @RequestBody MeetingCancelRequest request) {
        meetingService.cancel(meetingId, request.userId());
    }
}
