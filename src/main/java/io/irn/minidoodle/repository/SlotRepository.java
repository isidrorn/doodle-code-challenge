package io.irn.minidoodle.repository;

import io.irn.minidoodle.domain.Slot;
import io.irn.minidoodle.domain.SlotStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    /**
     * Each optional filter is CAST explicitly: PostgreSQL's extended query protocol
     * resolves a bind parameter's type at Parse time from its surrounding SQL context,
     * and a bare "? is null" (no comparison, no cast) gives it nothing to infer from —
     * "could not determine data type of parameter $n". H2 (used in tests) doesn't
     * enforce this, which is why this only surfaces against a real Postgres.
     *
     * <p>Deliberately returns just ids, not {@code Page<Slot>} — pagination (LIMIT/OFFSET) and a
     * collection fetch join don't combine safely in JPA: with a {@code @ManyToMany} fetch-joined
     * in the same query, Hibernate can't apply LIMIT/OFFSET in SQL (a fetch join can multiply rows)
     * and instead paginates the full result set in memory, which defeats the entire point of
     * paginating a "thousands of slots" endpoint. See {@link #findByIdInWithMeetings} for the
     * second step that loads the actual page's entities with {@code meetings} eagerly fetched.
     */
    @Query("""
            select s.id from Slot s
            where s.calendar.owner.id = :userId
              and (cast(:status as string)    is null or s.status = :status)
              and (cast(:from as Instant) is null or s.endTime >= :from)
              and (cast(:to   as Instant) is null or s.startTime <= :to)
            order by s.startTime asc
            """)
    Page<Long> searchIds(
            @Param("userId") Long userId,
            @Param("status") SlotStatus status,
            @Param("from")   Instant from,
            @Param("to")     Instant to,
            Pageable pageable
    );

    /**
     * Loads full {@code Slot} entities for a page of ids already selected by {@link #searchIds},
     * with {@code meetings} eagerly fetched — SlotMapper reads slot.getMeetings() to build
     * SlotResponse.meetingIds after the service's @Transactional method returns (open-in-view is
     * off), and @ManyToMany collections default to LAZY. Safe to fetch-join here since there's no
     * LIMIT/OFFSET on this query — the id list already fixes exactly which rows come back.
     */
    @EntityGraph(attributePaths = "meetings")
    @Query("select s from Slot s where s.id in :ids order by s.startTime asc")
    List<Slot> findByIdInWithMeetings(@Param("ids") List<Long> ids);

    /**
     * Overlap check — used to prevent double-booking within the same calendar.
     * Two intervals [a,b) and [c,d) overlap when a < d AND b > c.
     */
    @Query("""
            select count(s) > 0 from Slot s
            where s.calendar.owner.id = :userId
              and s.startTime < :endTime
              and s.endTime   > :startTime
              and (cast(:excludeId as long) is null or s.id != :excludeId)
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

    /** @EntityGraph("meetings") — see the note on search() above; getOne() maps this directly. */
    @EntityGraph(attributePaths = "meetings")
    @Query("select s from Slot s where s.calendar.owner.id = :userId and s.id = :slotId")
    Optional<Slot> findByUserIdAndSlotId(
            @Param("userId") Long userId,
            @Param("slotId") Long slotId
    );

    /**
     * FREE slots for a user within a range — used by MeetingService.confirm to check whether a
     * participant has full, contiguous slot coverage for a meeting's [startTime, endTime).
     */
    @Query("""
            select s from Slot s
            where s.calendar.owner.id = :userId
              and s.status = 'FREE'
              and s.startTime >= :startTime
              and s.endTime   <= :endTime
            order by s.startTime asc
            """)
    List<Slot> findFreeSlotsCovering(
            @Param("userId") Long userId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime
    );
}
