package co.edu.corhuila.csp.booking.domain.model;

/** What a client sees of one seat of a showtime: free, held for a pending reservation or confirmed. */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    CONFIRMED
}
