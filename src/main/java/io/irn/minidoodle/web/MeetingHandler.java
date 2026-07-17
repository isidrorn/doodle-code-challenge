package dev.isidro.queryverb.web;

import dev.isidro.queryverb.service.MeetingService;
import dev.isidro.queryverb.web.dto.MeetingCancelRequest;
import dev.isidro.queryverb.web.dto.MeetingCreateRequest;
import dev.isidro.queryverb.web.dto.VoteRequest;
import dev.isidro.queryverb.web.mapper.MeetingMapper;
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

    public ServerResponse getOne(ServerRequest request) throws Exception {
        return ok(meetingMapper.toResponse(meetingService.findById(meetingId(request))));
    }

    public ServerResponse vote(ServerRequest request) throws Exception {
        Long meetingId = meetingId(request);
        Long userId = Long.valueOf(request.pathVariable("userId"));
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
        return Long.valueOf(req.pathVariable("meetingId"));
    }

    private ServerResponse ok(Object body) throws Exception {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
