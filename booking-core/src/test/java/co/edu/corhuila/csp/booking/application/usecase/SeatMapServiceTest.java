package co.edu.corhuila.csp.booking.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.csp.booking.application.port.in.SeatMap;
import co.edu.corhuila.csp.booking.application.port.out.CatalogUnavailableException;
import co.edu.corhuila.csp.booking.application.port.out.LayoutSeat;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeLayout;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeNotAvailableException;
import co.edu.corhuila.csp.booking.domain.model.SeatStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeatMapServiceTest {

    private static final UUID SHOWTIME = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";

    private static final ShowtimeLayout LAYOUT = new ShowtimeLayout(SHOWTIME, "Movie", "Room 1",
            Instant.parse("2026-11-01T20:00:00Z"), Instant.parse("2026-11-01T22:00:00Z"), 12000,
            List.of(new LayoutSeat("A1", "A", 1, "STANDARD"), new LayoutSeat("A2", "A", 2, "VIP"),
                    new LayoutSeat("Z9", null, null, "ACCESSIBLE")));

    @Test
    void everySeatOfTheLayoutKeepsItsOrderAndItsValuesAndTheTakenOnesShowTheirStatus() {
        SeatMapService service = new SeatMapService(
                (id, correlation) -> LAYOUT,
                id -> Map.of("A2", SeatStatus.HELD, "Z9", SeatStatus.CONFIRMED));

        SeatMap map = service.seatMap(SHOWTIME, CORRELATION);

        assertEquals(LAYOUT, map.showtime());
        assertEquals(List.of("A1", "A2", "Z9"), map.seats().stream().map(s -> s.layout().label()).toList());
        assertEquals(List.of(SeatStatus.AVAILABLE, SeatStatus.HELD, SeatStatus.CONFIRMED),
                map.seats().stream().map(SeatMap.Seat::status).toList());
        assertNull(map.seats().get(2).layout().row());
        assertNull(map.seats().get(2).layout().number());
    }

    @Test
    void aTakenSeatThatIsNotInTheLayoutIsIgnored() {
        SeatMapService service = new SeatMapService((id, correlation) -> LAYOUT, id -> Map.of("B7", SeatStatus.HELD));

        SeatMap map = service.seatMap(SHOWTIME, CORRELATION);

        assertEquals(3, map.seats().size());
        assertEquals(List.of(SeatStatus.AVAILABLE, SeatStatus.AVAILABLE, SeatStatus.AVAILABLE),
                map.seats().stream().map(SeatMap.Seat::status).toList());
    }

    @Test
    void theFailuresOfCatalogReachTheCallerAndNoSeatIsReadFirst() {
        SeatMapService missing = new SeatMapService(
                (id, correlation) -> {
                    throw new ShowtimeNotAvailableException(id);
                },
                id -> {
                    throw new AssertionError("the seats are not read without a layout");
                });
        SeatMapService down = new SeatMapService(
                (id, correlation) -> {
                    throw new CatalogUnavailableException("timeout");
                },
                id -> {
                    throw new AssertionError("the seats are not read without a layout");
                });

        assertThrows(ShowtimeNotAvailableException.class, () -> missing.seatMap(SHOWTIME, CORRELATION));
        assertThrows(CatalogUnavailableException.class, () -> down.seatMap(SHOWTIME, CORRELATION));
    }
}
