package co.edu.corhuila.csp.booking.application.usecase;

import co.edu.corhuila.csp.booking.application.port.in.SeatMap;
import co.edu.corhuila.csp.booking.application.port.in.SeatMapUseCases;
import co.edu.corhuila.csp.booking.application.port.out.SeatOccupancy;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeCatalog;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeLayout;
import co.edu.corhuila.csp.booking.domain.model.SeatStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The seat map: the layout comes from Catalog and the status from the seats Booking has taken. A taken seat whose
 * label is not in the layout is ignored, and the layout is never changed.
 */
public class SeatMapService implements SeatMapUseCases {

    private final ShowtimeCatalog catalog;
    private final SeatOccupancy occupancy;

    public SeatMapService(ShowtimeCatalog catalog, SeatOccupancy occupancy) {
        this.catalog = catalog;
        this.occupancy = occupancy;
    }

    @Override
    public SeatMap seatMap(UUID showtimeId, String correlationId) {
        ShowtimeLayout layout = catalog.layoutOf(showtimeId, correlationId);
        Map<String, SeatStatus> taken = occupancy.takenSeats(showtimeId);
        List<SeatMap.Seat> seats = layout.seats().stream()
                .map(seat -> new SeatMap.Seat(seat, taken.getOrDefault(seat.label(), SeatStatus.AVAILABLE)))
                .toList();
        return new SeatMap(layout, seats);
    }
}
