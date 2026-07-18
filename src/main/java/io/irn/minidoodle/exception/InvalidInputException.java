package io.irn.minidoodle.exception;

/**
 * Input that fails a business rule (off-grid boundary, start not before end, non-participant
 * voting, oversized availability range) — mapped to 400. Structural validation (@NotBlank, @Size,
 * malformed JSON) never reaches here; the web layer rejects it first.
 */
public class InvalidInputException extends DomainException {

    public InvalidInputException(String message) {
        super(message);
    }
}
