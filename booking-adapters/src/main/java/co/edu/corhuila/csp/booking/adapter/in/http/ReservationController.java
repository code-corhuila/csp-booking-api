package co.edu.corhuila.csp.booking.adapter.in.http;

import co.edu.corhuila.csp.booking.application.port.in.CreateHoldInput;
import co.edu.corhuila.csp.booking.application.port.in.ReservationUseCases;
import co.edu.corhuila.csp.booking.application.port.out.CreateHoldResult;
import co.edu.corhuila.csp.booking.application.port.out.ReservationPage;
import co.edu.corhuila.csp.booking.application.port.out.ReservationQuery;
import co.edu.corhuila.csp.booking.domain.model.Reservation;
import co.edu.corhuila.csp.booking.domain.model.ReservationStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The four routes of the reservation contract, under the base of {@code booking-service.yaml}:
 * the hold whose key decides 201 or 200 (Norma 5.3.8), its confirmation, and the two reads that
 * only ever show the reservations of the caller. Every other answer of the contract is produced by
 * {@link ApiExceptionHandler}.
 */
@RestController
@Validated
public class ReservationController {

    private final ReservationUseCases useCases;

    public ReservationController(ReservationUseCases useCases) {
        this.useCases = useCases;
    }

    @PostMapping("/holds")
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

    /**
     * Confirms a HELD reservation of the caller. The transition itself is the guard against a
     * repeated call: a reservation can be confirmed once and the event is written once.
     *
     * <p>Unlike {@code POST /holds}, the key is only checked for its shape (required, 16 to 100
     * characters, as the contract says) and is never stored or compared: this route does not replay
     * the original 200. A retry after the first success is answered 422 and cannot be told apart
     * from a reservation that is expired or confirmed. Replaying would need the key to be stored by
     * {@code csp-booking-db}, which {@code booking.idempotency_key} does not allow today (one row
     * per hold).
     */
    @PostMapping("/reservations/{reservationId}/confirm")
    ReservationResponse confirm(
            @PathVariable UUID reservationId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 100) String idempotencyKey,
            HttpServletRequest http) {
        return ReservationResponse.of(useCases.confirmReservation(
                userIdOf(http), reservationId, MDC.get(CorrelationIdFilter.TRACE_ID_MDC_KEY)));
    }

    /**
     * One reservation for its owner: the same read answers 403 when the id belongs to somebody
     * else and 404 when no reservation has it (booking-service.yaml, {@code getReservation}).
     */
    @GetMapping("/reservations/{reservationId}")
    ReservationResponse reservation(@PathVariable UUID reservationId, HttpServletRequest http) {
        return ReservationResponse.of(useCases.getReservation(userIdOf(http), reservationId));
    }

    /**
     * The page of the caller, newest first, with the window and the filters of the contract. A
     * status the enumeration does not know and an instant that is not RFC 3339 are 400; a page or
     * a limit outside the bounds of the contract is refused by the port that builds the window,
     * which the answer turns into the same 400 instead of a 500.
     */
    @GetMapping("/reservations")
    ReservationListResponse reservations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) Instant createdBefore,
            HttpServletRequest http) {
        ReservationPage result = useCases.listReservations(
                userIdOf(http), new ReservationQuery(page, limit, status, createdBefore));
        return ReservationListResponse.of(result, page, limit);
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
