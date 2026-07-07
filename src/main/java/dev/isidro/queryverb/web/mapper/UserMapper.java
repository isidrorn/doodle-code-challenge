package dev.isidro.queryverb.web.mapper;

import dev.isidro.queryverb.domain.User;
import dev.isidro.queryverb.web.dto.UserResponse;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
}
