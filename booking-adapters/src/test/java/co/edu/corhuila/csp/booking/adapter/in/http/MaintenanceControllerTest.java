package co.edu.corhuila.csp.booking.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.corhuila.csp.booking.application.port.in.ReservationUseCases;
import co.edu.corhuila.csp.booking.application.port.out.ExpireHoldsResult;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The internal maintenance endpoint of HU-BOOKING-002: the expiration sweep calls it with its own
 * correlation id and reads how many reservations expired and how many remain.
 */
class MaintenanceControllerTest {

    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";

    private final ReservationUseCases useCases = mock(ReservationUseCases.class);
    private final MaintenanceController controller = new MaintenanceController(useCases);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void theSweepAnswersTheExpiredAndRemainingCounts() {
        when(useCases.expireHolds(anyInt(), anyString())).thenReturn(new ExpireHoldsResult(5, 2));

        var response = controller.expireHolds(CORRELATION);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = response.getBody();
        assertNotNull(body);
        assertEquals(5, body.expired());
        assertEquals(2, body.remaining());
        verify(useCases).expireHolds(100, CORRELATION);
    }

    @Test
    void theCorrelationIdOfTheRunTravelsToTheUseCase() throws Exception {
        when(useCases.expireHolds(anyInt(), anyString())).thenReturn(new ExpireHoldsResult(0, 0));

        mockMvc.perform(MockMvcRequestBuilders.post("/internal/maintenance/expire-holds")
                        .header("X-Correlation-Id", CORRELATION))
                .andReturn();

        verify(useCases).expireHolds(100, CORRELATION);
    }

    @Test
    void theExpiredCountDoesNotStayInTheLogContextOfTheThread() {
        when(useCases.expireHolds(anyInt(), anyString())).thenReturn(new ExpireHoldsResult(5, 2));

        controller.expireHolds(CORRELATION);

        assertNull(MDC.get("expired"));
    }

    @Test
    void theRouteAnswersTheCommonEnvelopeWhenTheCorrelationIdIsMissing() throws Exception {
        var result = mockMvc.perform(MockMvcRequestBuilders.post("/internal/maintenance/expire-holds"))
                .andReturn();

        assertEquals(HttpStatus.BAD_REQUEST, HttpStatus.valueOf(result.getResponse().getStatus()));
    }
}