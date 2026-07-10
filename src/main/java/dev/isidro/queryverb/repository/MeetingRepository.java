package dev.isidro.queryverb.repository;

import dev.isidro.queryverb.domain.Meeting;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    /**
     * Overrides the inherited findById to eagerly fetch slots: open-in-view is
     * disabled, so MeetingMapper reading meeting.getSlots() after the service's
     * @Transactional method returns would otherwise hit a closed session.
     */
    @Override
    @EntityGraph(attributePaths = "slots")
    Optional<Meeting> findById(Long id);
}
