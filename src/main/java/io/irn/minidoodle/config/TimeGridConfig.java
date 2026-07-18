package io.irn.minidoodle.config;

import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The scheduling time grid: every client-supplied boundary (slot start/end, meeting start/end,
 * availability range) must sit on a multiple of {@code scheduling.time-grid-minutes}. Slots carry
 * their own client-chosen {@code startTime}/{@code endTime} — the grid never derives or rewrites
 * stored data, it only <em>validates new writes</em>. That's what makes the parameter safe to
 * change on a live system: loosening it (30 → 5) keeps every stored boundary valid; tightening it
 * (5 → 30) only rejects off-grid boundaries on future writes, never touching existing rows.
 * See design-decisions-v7.md.
 *
 * <p>The compact constructor's fallback to 30 covers the case where {@code
 * scheduling.time-grid-minutes} is absent from the environment entirely (e.g.
 * application-test.yml) — constructor-bound {@code @ConfigurationProperties} binds a missing
 * property to the primitive default (0), not to whatever the record component's "natural"
 * default would be, since there's no {@code @DefaultValue} annotation here.
 */
@ConfigurationProperties(prefix = "scheduling")
public record TimeGridConfig(int timeGridMinutes) {

    public TimeGridConfig {
        if (timeGridMinutes <= 0) {
            timeGridMinutes = 30;
        }
    }

    public long stepSeconds() {
        return timeGridMinutes * 60L;
    }

    public boolean isAligned(Instant instant) {
        return instant.getEpochSecond() % stepSeconds() == 0;
    }
}
