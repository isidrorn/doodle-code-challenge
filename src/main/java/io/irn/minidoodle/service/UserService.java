package io.irn.minidoodle.service;

import io.irn.minidoodle.domain.Calendar;
import io.irn.minidoodle.domain.User;
import io.irn.minidoodle.exception.NotFoundException;
import io.irn.minidoodle.repository.UserRepository;
import io.irn.minidoodle.web.dto.UserCreateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Page<User> findAll(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public User findById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    public User create(UserCreateRequest request) {
        User user = new User(request.name(), request.email());
        new Calendar(user);
        userRepository.save(user);
        return user;
    }
}
