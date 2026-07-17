package io.irn.minidoodle.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.irn.minidoodle.domain.Calendar;
import io.irn.minidoodle.domain.Slot;
import io.irn.minidoodle.domain.SlotStatus;
import io.irn.minidoodle.domain.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class SlotRepositoryTest {

    @Autowired
    TestEntityManager em;
    @Autowired SlotRepository slotRepository;

    private Long userId;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        User user = new User("Test", "test@test.com");
        Calendar calendar = new Calendar(user);
        calendar.addSlot(new Slot(now, now.plus(1, ChronoUnit.HOURS)));                                   // FREE
        calendar.addSlot(new Slot(now.plus(2, ChronoUnit.HOURS), now.plus(3, ChronoUnit.HOURS)));         // FREE
        em.persistAndFlush(user);
        // manually mark second slot BUSY
        var busySlot = calendar.getSlots().get(1);
        busySlot.markBusy();
        em.persistAndFlush(busySlot);
        userId = user.getId();
    }

    @Test
    void searchWithNoFilterReturnsAll() {
        assertThat(slotRepository.search(userId, null, null, null)).hasSize(2);
    }

    @Test
    void searchFiltersByStatus() {
        var free = slotRepository.search(userId, SlotStatus.FREE, null, null);
        assertThat(free).hasSize(1);
        assertThat(free.getFirst().getStatus()).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void searchFiltersByTimeRange() {
        var result = slotRepository.search(userId, null, now, now.plus(1, ChronoUnit.HOURS));
        assertThat(result).hasSize(1);
    }

    @Test
    void detectsOverlapCorrectly() {
        assertThat(slotRepository.existsOverlap(userId, now.minus(30, ChronoUnit.MINUTES), now.plus(30, ChronoUnit.MINUTES), null))
                .isTrue();
        assertThat(slotRepository.existsOverlap(userId, now.plus(10, ChronoUnit.HOURS), now.plus(11, ChronoUnit.HOURS), null))
                .isFalse();
    }

    @Test
    void findFreeSlotsCoveringReturnsOnlyFreeSlotsWithinRange() {
        var result = slotRepository.findFreeSlotsCovering(userId, now, now.plus(3, ChronoUnit.HOURS));

        // The second seeded slot (now+2h..now+3h) was marked BUSY in setUp, so only the first is FREE.
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getStartTime()).isEqualTo(now);
    }

    @Test
    void findFreeSlotsCoveringExcludesSlotsOutsideRange() {
        var result = slotRepository.findFreeSlotsCovering(userId, now.plus(10, ChronoUnit.HOURS), now.plus(11, ChronoUnit.HOURS));

        assertThat(result).isEmpty();
    }
}
