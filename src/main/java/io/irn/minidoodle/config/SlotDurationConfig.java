package io.irn.minidoodle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Slot duration is a system parameter, not something a user chooses per-slot: every slot
 * (created individually or in bulk) must be exactly this long and start on this grid.
 *
 * <p>The compact constructor's fallback to 30 covers the case where {@code
 * scheduling.slot-duration-minutes} is absent from the environment entirely (e.g.
 * application-test.yml) — constructor-bound {@code @ConfigurationProperties} binds a missing
 * property to the primitive default (0), not to whatever the record component's "natural"
 * default would be, since there's no {@code @DefaultValue} annotation here.
 */
@ConfigurationProperties(prefix = "scheduling")
public record SlotDurationConfig(int slotDurationMinutes) {

    public SlotDurationConfig {
        if (slotDurationMinutes <= 0) {
            slotDurationMinutes = 30;
        }
    }
}
