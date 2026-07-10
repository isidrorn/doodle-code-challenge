package dev.isidro.queryverb.web;

import dev.isidro.queryverb.service.SlotService;
import dev.isidro.queryverb.web.dto.SlotCreateRequest;
import dev.isidro.queryverb.web.dto.SlotQueryFilter;
import dev.isidro.queryverb.web.dto.SlotUpdateRequest;
import dev.isidro.queryverb.web.mapper.SlotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Component
@RequiredArgsConstructor
public class SlotHandler {

    private final SlotService slotService;
    private final SlotMapper slotMapper;

    public ServerResponse getOne(ServerRequest request) throws Exception {
        return ok(slotMapper.toResponse(slotService.requireOwned(userId(request), slotId(request))));
    }

    public ServerResponse listAll(ServerRequest request) throws Exception {
        Long userId = userId(request);
        var body = slotService.query(userId, SlotQueryFilter.empty())
                .stream().map(slotMapper::toResponse).toList();
        return ok(body);
    }

    /**
     * HTTP QUERY: safe + idempotent read with a structured filter in the body.
     * Semantically equivalent to a GET with query params, but without URI length limits
     * and with a typed, self-documenting payload.
     */
    public ServerResponse query(ServerRequest request) throws Exception {
        Long userId = userId(request);
        SlotQueryFilter filter = parseFilter(request);
        var body = slotService.query(userId, filter)
                .stream().map(slotMapper::toResponse).toList();
        return ok(body);
    }

    public ServerResponse create(ServerRequest request) throws Exception {
        Long userId = userId(request);
        var slot = slotService.create(userId, request.body(SlotCreateRequest.class));
        return ServerResponse.status(201).contentType(MediaType.APPLICATION_JSON)
                .body(slotMapper.toResponse(slot));
    }

    public ServerResponse update(ServerRequest request) throws Exception {
        Long userId  = userId(request);
        Long slotId  = slotId(request);
        var slot = slotService.update(userId, slotId, request.body(SlotUpdateRequest.class));
        return ok(slotMapper.toResponse(slot));
    }

    public ServerResponse delete(ServerRequest request) throws Exception {
        slotService.delete(userId(request), slotId(request));
        return ServerResponse.noContent().build();
    }

    private Long userId(ServerRequest req) {
        return Long.valueOf(req.pathVariable("userId"));
    }

    private Long slotId(ServerRequest req) {
        return Long.valueOf(req.pathVariable("slotId"));
    }

    /**
     * Content-Length is unreliable here: the QUERY verb isn't recognized by every
     * HTTP client as carrying a body, so some clients omit the header even when a
     * body is present. Just attempt to parse it; an empty/absent body still maps
     * to "no filter" via the catch.
     */
    private SlotQueryFilter parseFilter(ServerRequest request) {
        try {
            return request.body(SlotQueryFilter.class);
        } catch (Exception e) {
            return SlotQueryFilter.empty();
        }
    }

    private ServerResponse ok(Object body) throws Exception {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
