package dev.isidro.queryverb.repository;

import dev.isidro.queryverb.domain.Slot;
import dev.isidro.queryverb.domain.SlotStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    @Query("""
            select s from Slot s
            where s.calendar.owner.id = :userId
              and (:status is null or s.status = :status)
              and (:from is null or s.endTime >= :from)
              and (:to   is null or s.startTime <= :to)
            order by s.startTime asc
            """)
    List<Slot> search(
            @Param("userId") Long userId,
            @Param("status") SlotStatus status,
            @Param("from")   Instant from,
            @Param("to")     Instant to
    );

    /**
     * Overlap check — used to prevent double-booking within the same calendar.
     * Two intervals [a,b) and [c,d) overlap when a < d AND b > c.
     */
    @Query("""
            select count(s) > 0 from Slot s
            where s.calendar.owner.id = :userId
              and s.startTime < :endTime
              and s.endTime   > :startTime
              and (:excludeId is null or s.id != :excludeId)
            """)
    boolean existsOverlap(
            @Param("userId")    Long userId,
            @Param("startTime") Instant startTime,
            @Param("endTime")   Instant endTime,
            @Param("excludeId") Long excludeId
    );

    /**
     * Load slot with a pessimistic write lock — used when booking a meeting
     * to prevent concurrent booking of the same slot between the check and the update.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Slot s where s.id = :id")
    Optional<Slot> findByIdForUpdate(@Param("id") Long id);

    @Query("select s from Slot s where s.calendar.owner.id = :userId and s.id = :slotId")
    Optional<Slot> findByUserIdAndSlotId(
            @Param("userId") Long userId,
            @Param("slotId") Long slotId
    );
}
