package io.irn.minidoodle.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * One slot-grid window within a requested [from, to) range where at least one of the queried users
 * is FREE — {@code freeUserIds} is the subset of the request's {@code userIds} free at this exact
 * window, not necessarily all of them. A caller wanting "only times that work for everyone" filters
 * client-side on {@code freeUserIds.size() == } the number of users they asked about; this endpoint
 * deliberately doesn't collapse that choice server-side, since "who's optional vs. required" is a
 * caller concern this response doesn't know about.
 */
public record AvailabilityWindow(Instant startTime, Instant endTime, List<Long> freeUserIds) {}
