package io.irn.minidoodle.web;

import io.irn.minidoodle.service.SlotService;
import io.irn.minidoodle.web.dto.SlotBulkCreateRequest;
import io.irn.minidoodle.web.dto.SlotQueryFilter;
import io.irn.minidoodle.web.dto.SlotUpdateRequest;
import io.irn.minidoodle.web.mapper.SlotMapper;
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
    private final RequestValidator requestValidator;

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

    /** Bulk-creates every requested slot in one transaction; see SlotService.create. */
    public ServerResponse create(ServerRequest request) throws Exception {
        Long userId = userId(request);
        var body = requestValidator.parseAndValidate(request, SlotBulkCreateRequest.class);
        var slots = slotService.create(userId, body)
                .stream().map(slotMapper::toResponse).toList();
        return ServerResponse.status(201).contentType(MediaType.APPLICATION_JSON).body(slots);
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
        return requestValidator.parseId(req, "userId");
    }

    private Long slotId(ServerRequest req) {
        return requestValidator.parseId(req, "slotId");
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
