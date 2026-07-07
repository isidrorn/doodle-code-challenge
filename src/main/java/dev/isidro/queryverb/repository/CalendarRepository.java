package dev.isidro.queryverb.repository;

import dev.isidro.queryverb.domain.Calendar;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarRepository extends JpaRepository<Calendar, Long> {

    Optional<Calendar> findByOwnerId(Long ownerId);
}
