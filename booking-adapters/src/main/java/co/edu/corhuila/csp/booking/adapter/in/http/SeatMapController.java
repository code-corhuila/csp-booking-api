package co.edu.corhuila.csp.booking.adapter.in.http;

import co.edu.corhuila.csp.booking.application.port.in.SeatMapUseCases;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The seat map of a showtime for any authenticated client (ADR-046). The token filter protects the route like every
 * other client route; the answers that are not a success are produced by {@link ApiExceptionHandler}. The map changes with
 * every hold, so it is never cached.
 */
@RestController
public class SeatMapController {

    private final SeatMapUseCases useCases;

    public SeatMapController(SeatMapUseCases useCases) {
        this.useCases = useCases;
    }

    @GetMapping("/showtimes/{showtimeId}/seats")
    ResponseEntity<SeatMapResponse> seatMap(@PathVariable UUID showtimeId) {
        SeatMapResponse body = SeatMapResponse.of(
                useCases.seatMap(showtimeId, MDC.get(CorrelationIdFilter.TRACE_ID_MDC_KEY)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
