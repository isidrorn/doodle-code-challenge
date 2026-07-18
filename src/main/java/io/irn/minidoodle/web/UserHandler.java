package io.irn.minidoodle.web;

import io.irn.minidoodle.service.UserService;
import io.irn.minidoodle.web.dto.PageResponse;
import io.irn.minidoodle.web.dto.UserCreateRequest;
import io.irn.minidoodle.web.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

@Component
@RequiredArgsConstructor
public class UserHandler {

    private final UserService userService;
    private final UserMapper userMapper;
    private final RequestValidator requestValidator;

    public ServerResponse listAll(ServerRequest request) throws Exception {
        var pageable = requestValidator.parsePageable(request, Sort.by(Sort.Direction.ASC, "id"));
        var page = userService.findAll(pageable).map(userMapper::toResponse);
        return ok(PageResponse.from(page));
    }

    public ServerResponse getOne(ServerRequest request) throws Exception {
        Long userId = requestValidator.parseId(request, "userId");
        return ok(userMapper.toResponse(userService.findById(userId)));
    }

    public ServerResponse create(ServerRequest request) throws Exception {
        var user = userService.create(requestValidator.parseAndValidate(request, UserCreateRequest.class));
        return ServerResponse.status(201).contentType(MediaType.APPLICATION_JSON)
                .body(userMapper.toResponse(user));
    }

    private ServerResponse ok(Object body) throws Exception {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
