package co.edu.corhuila.csp.booking.adapter.in.http;

import co.edu.corhuila.csp.booking.application.port.in.CreateHoldInput;
import co.edu.corhuila.csp.booking.application.port.in.ReservationUseCases;
import co.edu.corhuila.csp.booking.application.port.out.CreateHoldResult;
import co.edu.corhuila.csp.booking.domain.model.Reservation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The route the contract opens with: {@code POST /api/v1/booking/holds}. The key of the request
 * decides between its two answers, 201 when this call made the hold and 200 when it replays one
 * that already existed (Norma 5.3.8). Every other answer of the contract is produced by
 * {@link ApiExceptionHandler}.
 */
@RestController
@Validated
@RequestMapping("/holds")
public class ReservationController {

    private final ReservationUseCases useCases;

    public ReservationController(ReservationUseCases useCases) {
        this.useCases = useCases;
    }

    @PostMapping
    ResponseEntity<ReservationResponse> hold(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 100) String idempotencyKey,
            @Valid @RequestBody CreateHoldRequest request,
            HttpServletRequest http) {
        CreateHoldResult result = useCases.createHold(new CreateHoldInput(
                userIdOf(http), request.showtimeId(), request.seatLabels(), request.movieTitle(), request.roomName(),
                request.effectiveDurationSeconds(), idempotencyKey, MDC.get(CorrelationIdFilter.TRACE_ID_MDC_KEY)));

        Reservation reservation = result.reservation();
        if (result instanceof CreateHoldResult.Created) {
            return ResponseEntity.created(locationOf(http, reservation)).body(ReservationResponse.of(reservation));
        }
        return ResponseEntity.ok(ReservationResponse.of(reservation));
    }

    /** The user the filter authenticated: the {@code sub} of the token, an id of this platform. */
    private static UUID userIdOf(HttpServletRequest http) {
        return UUID.fromString((String) http.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE));
    }

    /** Where the Location of the 201 points: the route of the reservation this call created. */
    private static URI locationOf(HttpServletRequest http, Reservation reservation) {
        return URI.create(http.getContextPath() + "/reservations/" + reservation.id());
    }
}
