package co.edu.corhuila.csp.booking.adapter.in.http;

import co.edu.corhuila.csp.booking.application.port.in.CreateHoldInput;
import co.edu.corhuila.csp.booking.application.port.in.ReservationUseCases;
import co.edu.corhuila.csp.booking.application.port.out.CreateHoldResult;
import co.edu.corhuila.csp.booking.domain.model.Reservation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal operations of the workflow service (ADR-035), never routed by the gateway.
 * {@link JwtAuthenticationFilter} lets in only a service token whose {@code sub} is in
 * {@code WORKFLOW_SUBJECTS}, so these routes do not check who the caller is: the user of a hold is
 * the one in the body, and a confirmation acts on the reservation whoever owns it.
 */
@RestController
@Validated
@RequestMapping("/internal/workflow")
public class WorkflowController {

    private final ReservationUseCases useCases;

    public WorkflowController(ReservationUseCases useCases) {
        this.useCases = useCases;
    }

    /** The hold of {@code POST /holds}, for the user of the body; 201 when created and 200 on a replay. */
    @PostMapping("/holds")
    ResponseEntity<ReservationResponse> hold(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 100) String idempotencyKey,
            @Valid @RequestBody WorkflowCreateHoldRequest request,
            HttpServletRequest http) {
        CreateHoldResult result = useCases.createHold(new CreateHoldInput(
                request.userId(), request.showtimeId(), request.seatLabels(), request.movieTitle(), request.roomName(),
                request.effectiveDurationSeconds(), idempotencyKey, MDC.get(CorrelationIdFilter.TRACE_ID_MDC_KEY)));

        Reservation reservation = result.reservation();
        if (result instanceof CreateHoldResult.Created) {
            return ResponseEntity.created(ReservationController.locationOf(http, reservation))
                    .body(ReservationResponse.of(reservation));
        }
        return ResponseEntity.ok(ReservationResponse.of(reservation));
    }

    /**
     * Confirms a reservation whoever owns it. Idempotent by state: a reservation that is already
     * CONFIRMED answers 200 with itself and writes no second event. As on the client route the key
     * is only checked for its shape.
     */
    @PostMapping("/reservations/{reservationId}/confirm")
    ReservationResponse confirm(
            @PathVariable UUID reservationId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 100) String idempotencyKey) {
        return ReservationResponse.of(useCases.confirmReservationForWorkflow(
                reservationId, MDC.get(CorrelationIdFilter.TRACE_ID_MDC_KEY)));
    }
}
