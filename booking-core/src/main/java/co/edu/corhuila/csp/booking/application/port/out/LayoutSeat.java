package co.edu.corhuila.csp.booking.application.port.out;

import java.util.Objects;

/**
 * One seat of the room as Catalog stores it. {@code row} and {@code number} are null when the room does
 * not have them; Booking never invents them (ADR-046).
 */
public record LayoutSeat(String label, String row, Integer number, String type) {

    public LayoutSeat {
        Objects.requireNonNull(label, "the label of a seat is required");
        Objects.requireNonNull(type, "the type of a seat is required");
    }
}
