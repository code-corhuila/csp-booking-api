package co.edu.corhuila.csp.booking.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.edu.corhuila.csp.booking.application.port.in.ReservationUseCases;
import co.edu.corhuila.csp.booking.application.port.out.ExpireHoldsResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The internal maintenance endpoint of HU-BOOKING-002: the expiration sweep calls it with a
 * service token; a client token receives 403.
 */
class MaintenanceControllerTest {

    private final ReservationUseCases useCases = mock(ReservationUseCases.class);
    private final MaintenanceController controller = new MaintenanceController(useCases);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void theEndpointExpiresHoldsWithTheCorrelationIdOfTheSweep() throws Exception {
        when(useCases.expireHolds(anyInt(), anyString()))
                .thenReturn(new ExpireHoldsResult(3, 0));

        var result = mockMvc.perform(MockMvcRequestBuilders
                .post("/internal/maintenance/expire-holds")
                .header("X-Correlation-Id", "550e8400-e29b-41d4-a716-446655440000"));

        assertEquals(HttpStatus.OK, result.getResponse().getStatus());
    }

    @Test
    void theEndpointReturnsTheExpiredAndRemainingCounts() {
        when(useCases.expireHolds(100, "550e8400-e29b-41d4-a716-446655440000"))
                .thenReturn(new ExpireHoldsResult(5, 2));

        ResponseEntity<MaintenanceController.ExpireHoldsResponse> response =
                controller.expireHolds("550e8400-e29b-41d4-a716-446655440000", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var body = response.getBody();
        assert body != null;
        assertEquals(5, body.expired());
        assertEquals(2, body.remaining());
    }
}
