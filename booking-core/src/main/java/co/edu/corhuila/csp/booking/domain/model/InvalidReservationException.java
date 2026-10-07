package co.edu.corhuila.csp.booking.domain.model;

/**
 * A {@link Reservation} was built from values that no valid reservation can have, for example no
 * seat or a null id. It is a defect of the caller, not a rule evaluated against the state of the
 * system, so the HTTP adapter must not answer it with the 422 of {@link BusinessRuleViolationException}.
 */
public class InvalidReservationException extends DomainException {

    public InvalidReservationException(String message) {
        super(message);
    }
}
