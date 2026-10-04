package co.edu.corhuila.csp.booking.domain.model;

/** Another invariant of the domain forbids the operation, for example a seat that is not available. */
public class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(String message) {
        super(message);
    }
}
