package dev.isidro.queryverb.repository;

import dev.isidro.queryverb.domain.Meeting;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    /**
     * Overrides the inherited findById to eagerly fetch participants (and each participant's
     * user): open-in-view is disabled, so MeetingMapper reading participant.getUser().getName()
     * after the service's @Transactional method returns would otherwise hit a closed session.
     *
     * <p>slots is deliberately NOT in this graph — MeetingMapper no longer reads it (see
     * MeetingResponse), and the service methods that do (confirm/cancel) always touch it from
     * inside an active transaction, where lazy loading works regardless of the graph.
     */
    @Override
    @EntityGraph(attributePaths = {"participants", "participants.user"})
    Optional<Meeting> findById(Long id);
}
