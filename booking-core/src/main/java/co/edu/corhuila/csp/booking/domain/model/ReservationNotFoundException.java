package co.edu.corhuila.csp.booking.domain.model;

import java.util.UUID;

/** No reservation has the requested id. */
public class ReservationNotFoundException extends DomainException {

    public ReservationNotFoundException(UUID id) {
        super("reservation " + id + " not found");
    }
}
