package co.edu.corhuila.csp.booking.application.port.out;

import java.util.UUID;

/** Catalog has no available showtime with that id (unknown, not scheduled, or its movie or room is not usable). */
public class ShowtimeNotAvailableException extends RuntimeException {

    public ShowtimeNotAvailableException(UUID showtimeId) {
        super("showtime " + showtimeId + " is not available");
    }
}
