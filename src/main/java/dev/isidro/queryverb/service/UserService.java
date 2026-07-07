package dev.isidro.queryverb.service;

import dev.isidro.queryverb.domain.Calendar;
import dev.isidro.queryverb.domain.User;
import dev.isidro.queryverb.repository.CalendarRepository;
import dev.isidro.queryverb.repository.UserRepository;
import dev.isidro.queryverb.web.dto.UserCreateRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final CalendarRepository calendarRepository;

    @Transactional(readOnly = true)
    public List<User> findAll() {
        return userRepository.findAll();
    }

    @Transactional(readOnly = true)
    public User findById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
    }

    public User create(UserCreateRequest request) {
        User user = new User(request.name(), request.email());
        Calendar calendar = new Calendar(user);
        calendarRepository.save(calendar);
        return user;
    }
}
