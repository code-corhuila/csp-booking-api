package co.edu.corhuila.csp.booking.application.port.out;

/**
 * The outcome of one expiration sweep run: how many reservations were expired and how many
 * remain overdue for the next run.
 */
public record ExpireHoldsResult(int expired, int remaining) {
}
