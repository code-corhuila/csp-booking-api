package co.edu.corhuila.csp.booking.adapter.in.http;

import co.edu.corhuila.csp.booking.application.port.in.SeatMap;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeLayout;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code SeatMapResponse} of {@code booking-service.yaml} 3.3.0: no user, hold or reservation identifier ever reaches it. */
public record SeatMapResponse(UUID showtimeId, String movieTitle, String roomName, Instant startsAt, Instant endsAt,
        long price, List<Seat> seats) {

    public static SeatMapResponse of(SeatMap map) {
        ShowtimeLayout showtime = map.showtime();
        return new SeatMapResponse(showtime.showtimeId(), showtime.movieTitle(), showtime.roomName(), showtime.startsAt(),
                showtime.endsAt(), showtime.price(),
                map.seats().stream().map(seat -> new Seat(seat.layout().label(), seat.layout().row(),
                        seat.layout().number(), seat.layout().type(), seat.status().name())).toList());
    }

    /** {@code MapSeat}: {@code row} and {@code number} are omitted when Catalog has none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Seat(String label, String row, Integer number, String type, String status) {
    }
}
