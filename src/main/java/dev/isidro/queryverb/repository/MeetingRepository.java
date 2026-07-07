package dev.isidro.queryverb.repository;

import dev.isidro.queryverb.domain.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {
}
