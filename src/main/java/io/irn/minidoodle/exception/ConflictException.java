package io.irn.minidoodle.exception;

/**
 * A valid request that conflicts with current state (overlapping slot, slot booked in a confirmed
 * meeting, illegal meeting state transition, duplicate email) — mapped to 409.
 */
public class ConflictException extends DomainException {

    public ConflictException(String message) {
        super(message);
    }
}
