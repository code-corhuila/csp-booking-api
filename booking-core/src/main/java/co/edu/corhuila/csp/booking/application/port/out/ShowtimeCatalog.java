package co.edu.corhuila.csp.booking.application.port.out;

import java.util.UUID;

/**
 * Output port to Catalog: the layout of a showtime, read with the service credential (ADR-020, ADR-046).
 * The core does not know HTTP.
 */
public interface ShowtimeCatalog {

    /**
     * @throws ShowtimeNotAvailableException when Catalog has no available showtime with that id
     * @throws CatalogUnavailableException when Catalog cannot be reached or answers a server error
     */
    ShowtimeLayout layoutOf(UUID showtimeId, String correlationId);
}
