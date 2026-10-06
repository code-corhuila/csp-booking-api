package co.edu.corhuila.csp.booking.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.booking.adapter.in.http.ReservationResponse;
import co.edu.corhuila.csp.booking.domain.model.Reservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * The contract declares every instant with {@code format: date-time}. With the configuration this
 * service boots with, the instants of a reservation leave as ISO-8601 and never as the number of
 * an epoch, which no client of the platform could read.
 */
@JsonTest
class ReservationResponseJsonTest {

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SHOWTIME = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private ObjectMapper mapper;

    @Test
    void theInstantsOfAReservationLeaveAsTheDateTimeOfTheContract() throws Exception {
        Reservation reservation = Reservation.hold(UUID.randomUUID(), USER, SHOWTIME, List.of("A1"),
                Duration.ofSeconds(600), Instant.parse("2026-10-05T10:15:30.123456Z"), "Movie", "Room 1", 0);

        String json = mapper.writeValueAsString(ReservationResponse.of(reservation));

        assertTrue(json.contains("\"createdAt\":\"2026-10-05T10:15:30.123456Z\""), json);
        assertTrue(json.contains("\"expiresAt\":\"2026-10-05T10:25:30.123456Z\""), json);
    }
}
