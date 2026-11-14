package co.edu.corhuila.csp.booking.application.port.in;

import co.edu.corhuila.csp.booking.application.port.out.LayoutSeat;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeLayout;
import co.edu.corhuila.csp.booking.domain.model.SeatStatus;
import java.util.List;

/** The layout of a showtime with the status of each of its seats. */
public record SeatMap(ShowtimeLayout showtime, List<Seat> seats) {

    public SeatMap {
        seats = List.copyOf(seats);
    }

    /** One seat of the layout and what it is now. */
    public record Seat(LayoutSeat layout, SeatStatus status) {
    }
}
