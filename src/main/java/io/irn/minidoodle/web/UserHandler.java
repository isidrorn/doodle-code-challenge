package dev.isidro.queryverb.web;

import dev.isidro.queryverb.service.UserService;
import dev.isidro.queryverb.web.dto.UserCreateRequest;
import dev.isidro.queryverb.web.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
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
        var body = userService.findAll().stream().map(userMapper::toResponse).toList();
        return ok(body);
    }

    public ServerResponse getOne(ServerRequest request) throws Exception {
        Long userId = Long.valueOf(request.pathVariable("userId"));
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
