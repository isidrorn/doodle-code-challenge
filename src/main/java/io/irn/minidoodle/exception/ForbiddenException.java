package io.irn.minidoodle.exception;

/** The caller is known but not allowed to perform this action (non-organizer cancel) — mapped to 403. */
public class ForbiddenException extends DomainException {

    public ForbiddenException(String message) {
        super(message);
    }
}
