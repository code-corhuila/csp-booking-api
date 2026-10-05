package co.edu.corhuila.csp.booking.adapter.in.http;

import co.edu.corhuila.csp.booking.application.port.in.ReservationUseCases;
import co.edu.corhuila.csp.booking.application.port.out.ExpireHoldsResult;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal maintenance operations called by csp-worker, never routed by the gateway. The worker
 * authenticates with its service token; a client token receives 403 (Norma 5.7.4).
 */
@RestController
@RequestMapping("/internal/maintenance")
public class MaintenanceController {

    private final ReservationUseCases useCases;

    public MaintenanceController(ReservationUseCases useCases) {
        this.useCases = useCases;
    }

    /**
     * Expires HELD reservations past their hold time (HU-BOOKING-002). The worker calls this
     * endpoint on a schedule; each run carries its own {@code X-Correlation-Id}.
     */
    @PostMapping("/expire-holds")
    ResponseEntity<ExpireHoldsResponse> expireHolds(
            @RequestHeader("X-Correlation-Id") String correlationId) {
        ExpireHoldsResult result = useCases.expireHolds(100, correlationId);
        MDC.put("expired", String.valueOf(result.expired()));
        return ResponseEntity.ok(new ExpireHoldsResponse(result.expired(), result.remaining()));
    }

    /**
     * The response of the expiration sweep: how many reservations were expired and how many
     * remain overdue for the next run.
     */
    public record ExpireHoldsResponse(int expired, int remaining) {
    }
}
