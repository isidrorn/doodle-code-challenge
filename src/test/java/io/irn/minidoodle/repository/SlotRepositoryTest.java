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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    private static final PageRequest ALL = PageRequest.of(0, 10, Sort.by("startTime"));

    @Test
    void searchWithNoFilterReturnsAll() {
        assertThat(slotRepository.searchIds(userId, null, null, null, ALL).getContent()).hasSize(2);
    }

    @Test
    void searchFiltersByStatus() {
        var ids = slotRepository.searchIds(userId, SlotStatus.FREE, null, null, ALL).getContent();
        assertThat(ids).hasSize(1);
        var free = slotRepository.findByIdInWithMeetings(ids);
        assertThat(free.getFirst().getStatus()).isEqualTo(SlotStatus.FREE);
    }

    @Test
    void searchFiltersByTimeRange() {
        var result = slotRepository.searchIds(userId, null, now, now.plus(1, ChronoUnit.HOURS), ALL).getContent();
        assertThat(result).hasSize(1);
    }

    @Test
    void searchIdsRespectsPageSizeAndReportsTotalElements() {
        var page = slotRepository.searchIds(userId, null, null, null, PageRequest.of(0, 1, Sort.by("startTime")));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
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

    /**
     * Unlike findFreeSlotsCovering (contained-only), this returns slots merely *overlapping* the
     * range — a slot that starts before the range still counts. The BUSY slot never does.
     */
    @Test
    void findFreeSlotsOverlappingIncludesPartialOverlaps_butNeverBusySlots() {
        var result = slotRepository.findFreeSlotsOverlapping(
                userId, now.plus(30, ChronoUnit.MINUTES), now.plus(3, ChronoUnit.HOURS));

        // FREE slot [now, now+1h) overlaps the range even though it starts before it;
        // the [now+2h, now+3h) slot inside the range is BUSY and excluded.
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getStartTime()).isEqualTo(now);
    }
}
