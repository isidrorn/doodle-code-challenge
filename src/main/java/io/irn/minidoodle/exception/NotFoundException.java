package io.irn.minidoodle.exception;

/** A referenced resource (user, slot, meeting, calendar) does not exist — mapped to 404. */
public class NotFoundException extends DomainException {

    public NotFoundException(String message) {
        super(message);
    }
}
