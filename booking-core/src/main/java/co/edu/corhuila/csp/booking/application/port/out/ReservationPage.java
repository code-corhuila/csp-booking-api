package co.edu.corhuila.csp.booking.application.port.out;

import co.edu.corhuila.csp.booking.domain.model.Reservation;
import java.util.List;
import java.util.Objects;

/** One page of the reservations of a user: the items and how many exist in total. */
public record ReservationPage(List<Reservation> items, long total) {

    public ReservationPage {
        Objects.requireNonNull(items, "the items are required");
    }
}
