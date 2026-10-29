package co.edu.corhuila.csp.booking.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The showtime and the seats of its room, in the stored order of Catalog. */
public record ShowtimeLayout(UUID showtimeId, String movieTitle, String roomName, Instant startsAt, Instant endsAt,
        long price, List<LayoutSeat> seats) {

    public ShowtimeLayout {
        seats = List.copyOf(seats);
    }
}
