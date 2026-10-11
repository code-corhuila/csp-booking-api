package co.edu.corhuila.csp.booking.domain.model;

/**
 * Another invariant of the domain forbids the operation, for example a seat that is not available
 * or a hold duration out of range. It is a rule evaluated against the request or the state, so the
 * HTTP adapter answers it with 422; a malformed {@link Reservation} is {@link InvalidReservationException}.
 */
public class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(String message) {
        super(message);
    }
}
