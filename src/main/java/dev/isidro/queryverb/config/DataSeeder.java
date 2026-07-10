package dev.isidro.queryverb.config;

import dev.isidro.queryverb.domain.Calendar;
import dev.isidro.queryverb.domain.Slot;
import dev.isidro.queryverb.domain.User;
import dev.isidro.queryverb.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedUser("Alice", "alice@example.dev");
        seedUser("Bob", "bob@example.dev");
        log.info("Seed complete. Use the logged calendar IDs to call the API.");
    }

    private void seedUser(String name, String email) {
        User user = new User(name, email);
        Calendar calendar = new Calendar(user);

        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        calendar.addSlot(new Slot(now, now.plus(1, ChronoUnit.HOURS)));
        calendar.addSlot(new Slot(now.plus(2, ChronoUnit.HOURS), now.plus(3, ChronoUnit.HOURS)));
        calendar.addSlot(new Slot(now.plus(1, ChronoUnit.DAYS),  now.plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS)));

        userRepository.save(user);
        log.info("Seeded user='{}' userId={} calendarId={}", name, user.getId(), calendar.getId());
    }
}
