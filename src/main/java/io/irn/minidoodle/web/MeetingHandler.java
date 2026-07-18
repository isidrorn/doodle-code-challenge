package io.irn.minidoodle.web;

import io.irn.minidoodle.service.MeetingService;
import io.irn.minidoodle.web.dto.AvailabilityQuery;
import io.irn.minidoodle.web.dto.MeetingCancelRequest;
import io.irn.minidoodle.web.dto.MeetingCreateRequest;
import io.irn.minidoodle.web.dto.VoteRequest;
import io.irn.minidoodle.web.mapper.MeetingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Component
@RequiredArgsConstructor
public class MeetingHandler {

    private final MeetingService meetingService;
    private final MeetingMapper meetingMapper;
    private final RequestValidator requestValidator;

    public ServerResponse create(ServerRequest request) throws Exception {
        var body = requestValidator.parseAndValidate(request, MeetingCreateRequest.class);
        var meeting = meetingService.create(body);
        return ServerResponse.status(201).contentType(MediaType.APPLICATION_JSON)
                .body(meetingMapper.toResponse(meeting));
    }

    /**
     * HTTP QUERY: "when, within this range, is at least one of these users free?" — unlike
     * SlotHandler.query, every field in the body is required, so this goes through the normal
     * parseAndValidate path rather than SlotHandler.parseFilter's "empty body means no filter"
     * convention (there's no sensible default set of users/range to fall back to).
     */
    public ServerResponse availability(ServerRequest request) throws Exception {
        var body = requestValidator.parseAndValidate(request, AvailabilityQuery.class);
        var windows = meetingService.availability(body.userIds(), body.from(), body.to());
        return ok(windows);
    }

    public ServerResponse getOne(ServerRequest request) throws Exception {
        return ok(meetingMapper.toResponse(meetingService.findById(meetingId(request))));
    }

    public ServerResponse vote(ServerRequest request) throws Exception {
        Long meetingId = meetingId(request);
        Long userId = requestValidator.parseId(request, "userId");
        var body = requestValidator.parseAndValidate(request, VoteRequest.class);
        var meeting = meetingService.vote(meetingId, userId, body.vote());
        return ok(meetingMapper.toResponse(meeting));
    }

    public ServerResponse cancel(ServerRequest request) throws Exception {
        Long meetingId = meetingId(request);
        var body = requestValidator.parseAndValidate(request, MeetingCancelRequest.class);
        meetingService.cancel(meetingId, body.userId());
        return ServerResponse.noContent().build();
    }

    private Long meetingId(ServerRequest req) {
        return requestValidator.parseId(req, "meetingId");
    }

    private ServerResponse ok(Object body) throws Exception {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
