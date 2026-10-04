package co.edu.corhuila.csp.booking.domain.model;

/** Lifecycle of a reservation: it starts HELD and ends CONFIRMED or EXPIRED. */
public enum ReservationStatus {
    HELD,
    CONFIRMED,
    EXPIRED
}
