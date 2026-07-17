package io.irn.minidoodle.web.mapper;

import io.irn.minidoodle.domain.User;
import io.irn.minidoodle.web.dto.UserResponse;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
}
