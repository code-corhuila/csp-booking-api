package co.edu.corhuila.csp.booking.adapter.in.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.csp.booking.application.port.in.SeatMap;
import co.edu.corhuila.csp.booking.application.port.in.SeatMapUseCases;
import co.edu.corhuila.csp.booking.application.port.out.CatalogUnavailableException;
import co.edu.corhuila.csp.booking.application.port.out.LayoutSeat;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeLayout;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeNotAvailableException;
import co.edu.corhuila.csp.booking.domain.model.SeatStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** {@code GET /showtimes/{showtimeId}/seats} with the answers {@code booking-service.yaml} 3.3.0 declares for it. */
class SeatMapControllerTest {

    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";
    private static final UUID SHOWTIME = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private SeatMapUseCases useCases;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCases = mock(SeatMapUseCases.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SeatMapController(useCases))
                .addFilters(new CorrelationIdFilter())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static SeatMap map() {
        ShowtimeLayout layout = new ShowtimeLayout(SHOWTIME, "Movie", "Room 1", Instant.parse("2026-11-01T20:00:00Z"),
                Instant.parse("2026-11-01T22:00:00Z"), 12000, List.of());
        return new SeatMap(layout, List.of(
                new SeatMap.Seat(new LayoutSeat("A1", "A", 1, "STANDARD"), SeatStatus.AVAILABLE),
                new SeatMap.Seat(new LayoutSeat("Z9", null, null, "ACCESSIBLE"), SeatStatus.HELD)));
    }

    @Test
    void theMapAnswers200WithTheSeatsInOrderAndNeverCachesIt() throws Exception {
        when(useCases.seatMap(eq(SHOWTIME), any())).thenReturn(map());

        mockMvc.perform(get("/showtimes/{id}/seats", SHOWTIME).header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.showtimeId").value(SHOWTIME.toString()))
                .andExpect(jsonPath("$.movieTitle").value("Movie"))
                .andExpect(jsonPath("$.roomName").value("Room 1"))
                // The shape of the instants is the date-time of the contract; it is asserted against the
                // configuration the service boots with in SeatMapResponseJsonTest.
                .andExpect(jsonPath("$.startsAt").isNotEmpty())
                .andExpect(jsonPath("$.price").value(12000))
                .andExpect(jsonPath("$.seats[0].label").value("A1"))
                .andExpect(jsonPath("$.seats[0].row").value("A"))
                .andExpect(jsonPath("$.seats[0].number").value(1))
                .andExpect(jsonPath("$.seats[0].type").value("STANDARD"))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.seats[1].label").value("Z9"))
                .andExpect(jsonPath("$.seats[1].row").doesNotExist())
                .andExpect(jsonPath("$.seats[1].number").doesNotExist())
                .andExpect(jsonPath("$.seats[1].status").value("HELD"))
                .andExpect(jsonPath("$.userId").doesNotExist());

        verify(useCases).seatMap(SHOWTIME, CORRELATION);
    }

    @Test
    void aShowtimeThatIsNotAvailableAnswers404() throws Exception {
        when(useCases.seatMap(any(), any())).thenThrow(new ShowtimeNotAvailableException(SHOWTIME));

        mockMvc.perform(get("/showtimes/{id}/seats", SHOWTIME))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void aCatalogThatCannotAnswerAnswers503() throws Exception {
        when(useCases.seatMap(any(), any())).thenThrow(new CatalogUnavailableException("timeout"));

        mockMvc.perform(get("/showtimes/{id}/seats", SHOWTIME))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void aCatalogThatRefusesTheCredentialAnswers500WithoutItsDetail() throws Exception {
        when(useCases.seatMap(any(), any())).thenThrow(new IllegalStateException("catalog refused the service credential with 403"));

        mockMvc.perform(get("/showtimes/{id}/seats", SHOWTIME))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    @Test
    void anIdentifierThatIsNotAUuidAnswers400() throws Exception {
        mockMvc.perform(get("/showtimes/{id}/seats", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
