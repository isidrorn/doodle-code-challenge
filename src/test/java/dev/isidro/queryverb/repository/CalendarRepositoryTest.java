package dev.isidro.queryverb.repository;

import static org.assertj.core.api.Assertions.assertThat;

import dev.isidro.queryverb.domain.Calendar;
import dev.isidro.queryverb.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class CalendarRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired CalendarRepository calendarRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        User user = new User("Test", "test@test.com");
        new Calendar(user);
        em.persistAndFlush(user);
        userId = user.getId();
    }

    @Test
    void findByOwnerIdForUpdate_returnsCalendar_whenExists() {
        assertThat(calendarRepository.findByOwnerIdForUpdate(userId)).isPresent();
    }

    @Test
    void findByOwnerIdForUpdate_returnsEmpty_whenOwnerNotFound() {
        assertThat(calendarRepository.findByOwnerIdForUpdate(999L)).isEmpty();
    }
}
