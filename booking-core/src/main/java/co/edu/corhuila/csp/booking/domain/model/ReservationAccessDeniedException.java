package co.edu.corhuila.csp.booking.domain.model;

import java.util.UUID;

/** The caller is authenticated but the reservation belongs to another user. */
public class ReservationAccessDeniedException extends DomainException {

    public ReservationAccessDeniedException(UUID reservationId, UUID callerId) {
        super("reservation " + reservationId + " belongs to another user (caller " + callerId + ")");
    }
}
