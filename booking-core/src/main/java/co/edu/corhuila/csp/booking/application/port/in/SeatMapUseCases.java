package co.edu.corhuila.csp.booking.application.port.in;

import java.util.UUID;

/** What an authenticated client can ask about the seats of a showtime. */
public interface SeatMapUseCases {

    /**
     * The seat map of a showtime (ADR-046): the layout of Catalog with the status of each seat. It is a view; the hold
     * stays the only authority on whether a seat can be taken.
     *
     * @throws co.edu.corhuila.csp.booking.application.port.out.ShowtimeNotAvailableException when Catalog has no such showtime
     * @throws co.edu.corhuila.csp.booking.application.port.out.CatalogUnavailableException when Catalog cannot answer
     */
    SeatMap seatMap(UUID showtimeId, String correlationId);
}
