package io.irn.minidoodle.web;

import io.irn.minidoodle.service.UserService;
import io.irn.minidoodle.web.dto.PageResponse;
import io.irn.minidoodle.web.dto.UserCreateRequest;
import io.irn.minidoodle.web.dto.UserResponse;
import io.irn.minidoodle.web.mapper.UserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    /**
     * Paginated. An out-of-range page/size is a 400 (via the {@code @Min}/{@code @Max}
     * constraints), not silently clamped — an out-of-range value is much more likely a client bug
     * than an intentional request for "as much as you'll give me".
     */
    @GetMapping
    public PageResponse<UserResponse> listAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = userService.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id")))
                .map(userMapper::toResponse);
        return PageResponse.from(result);
    }

    @GetMapping("/{userId}")
    public UserResponse getOne(@PathVariable Long userId) {
        return userMapper.toResponse(userService.findById(userId));
    }

    /** Also creates the user's Calendar — cascaded from the User aggregate, see UserService. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody UserCreateRequest request) {
        return userMapper.toResponse(userService.create(request));
    }
}
