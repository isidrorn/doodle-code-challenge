package dev.isidro.queryverb.repository;

import dev.isidro.queryverb.domain.Calendar;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarRepository extends JpaRepository<Calendar, Long> {

    /**
     * Eagerly fetches slots so callers can mutate the collection (e.g. addSlot)
     * even outside an active persistence context, without a LazyInitializationException.
     */
    @EntityGraph(attributePaths = "slots")
    Optional<Calendar> findByOwnerId(Long ownerId);
}
