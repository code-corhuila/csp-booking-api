package co.edu.corhuila.csp.booking.domain.model;

/** The caller is authenticated but the reservation belongs to another user. */
public class ReservationAccessDeniedException extends DomainException {

    public ReservationAccessDeniedException() {
        super("the reservation belongs to another user");
    }
}
