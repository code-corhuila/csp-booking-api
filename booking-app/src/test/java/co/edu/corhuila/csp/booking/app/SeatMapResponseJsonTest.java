package co.edu.corhuila.csp.booking.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.booking.adapter.in.http.SeatMapResponse;
import co.edu.corhuila.csp.booking.application.port.in.SeatMap;
import co.edu.corhuila.csp.booking.application.port.out.LayoutSeat;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeLayout;
import co.edu.corhuila.csp.booking.domain.model.SeatStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * {@code SeatMapResponse} of {@code booking-service.yaml} 3.3.0 with the configuration this service boots with: the
 * instants are {@code date-time} strings, and {@code row} and {@code number} are omitted when Catalog has none.
 */
@JsonTest
class SeatMapResponseJsonTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void theInstantsLeaveAsDateTimeAndAMissingRowOrNumberIsOmitted() throws Exception {
        ShowtimeLayout layout = new ShowtimeLayout(UUID.randomUUID(), "Movie", "Room 1",
                Instant.parse("2026-11-01T20:00:00Z"), Instant.parse("2026-11-01T22:00:00Z"), 12000, List.of());
        SeatMap map = new SeatMap(layout, List.of(
                new SeatMap.Seat(new LayoutSeat("A1", "A", 1, "STANDARD"), SeatStatus.AVAILABLE),
                new SeatMap.Seat(new LayoutSeat("Z9", null, null, "ACCESSIBLE"), SeatStatus.CONFIRMED)));

        String json = mapper.writeValueAsString(SeatMapResponse.of(map));

        assertTrue(json.contains("\"startsAt\":\"2026-11-01T20:00:00Z\""), json);
        assertTrue(json.contains("\"endsAt\":\"2026-11-01T22:00:00Z\""), json);
        assertTrue(json.contains("{\"label\":\"A1\",\"row\":\"A\",\"number\":1,\"type\":\"STANDARD\",\"status\":\"AVAILABLE\"}"), json);
        assertTrue(json.contains("{\"label\":\"Z9\",\"type\":\"ACCESSIBLE\",\"status\":\"CONFIRMED\"}"), json);
        assertFalse(json.contains("\"row\":null"), json);
    }
}
