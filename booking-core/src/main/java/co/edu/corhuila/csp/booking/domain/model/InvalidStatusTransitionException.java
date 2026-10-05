package co.edu.corhuila.csp.booking.domain.model;

/** The status rules forbid the requested change, for example confirming an expired reservation. */
public class InvalidStatusTransitionException extends DomainException {

    public InvalidStatusTransitionException(String message) {
        super(message);
    }
}
