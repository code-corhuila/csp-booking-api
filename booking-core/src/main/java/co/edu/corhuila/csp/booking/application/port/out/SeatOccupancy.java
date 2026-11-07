package co.edu.corhuila.csp.booking.application.port.out;

import co.edu.corhuila.csp.booking.domain.model.SeatStatus;
import java.util.Map;
import java.util.UUID;

/** Output port: which seats of a showtime are taken. It is the same fact that makes a second hold of the seat fail. */
public interface SeatOccupancy {

    /** The seats of the showtime that are {@code HELD} or {@code CONFIRMED}, by label; free seats are absent. */
    Map<String, SeatStatus> takenSeats(UUID showtimeId);
}
