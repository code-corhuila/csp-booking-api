package co.edu.corhuila.csp.booking.application.port.in;

import co.edu.corhuila.csp.booking.application.port.out.CreateHoldResult;
import co.edu.corhuila.csp.booking.application.port.out.ExpireHoldsResult;
import co.edu.corhuila.csp.booking.application.port.out.ReservationPage;
import co.edu.corhuila.csp.booking.application.port.out.ReservationQuery;
import co.edu.corhuila.csp.booking.domain.model.Reservation;
import java.util.UUID;

/** Everything an authenticated client can ask of the reservation aggregate. */
public interface ReservationUseCases {

    /**
     * Holds the requested seats of a showtime for the authenticated user, atomically: if any seat
     * is taken, nothing is created.
     *
     * @return the reservation and whether this call created it or replayed an earlier one
     */
    CreateHoldResult createHold(CreateHoldInput input);

    /**
     * One reservation for its caller. The id answers 404 and the owner answers 403, so both checks
     * belong to the same read.
     */
    Reservation getReservation(UUID callerId, UUID reservationId);

    /**
     * Confirms a HELD reservation of the caller, all its seats at once (HU-BOOKING-003). A hold
     * past its expiration time is refused even when the sweep did not release it yet.
     *
     * @param correlationId the correlation id of the request, written to the {@code BookingConfirmed} event
     * @return the CONFIRMED reservation
     * @throws co.edu.corhuila.csp.booking.domain.model.InvalidStatusTransitionException when the
     *         reservation is expired or already confirmed
     */
    Reservation confirmReservation(UUID callerId, UUID reservationId, String correlationId);

    /**
     * Confirms a HELD reservation of any user for the workflow service (ADR-035). It is idempotent
     * by state: a reservation that is already CONFIRMED is returned as it is and nothing is written,
     * so a retry after a lost answer is told apart from a refusal.
     *
     * @return the CONFIRMED reservation
     * @throws co.edu.corhuila.csp.booking.domain.model.ReservationNotFoundException when no reservation has the id
     * @throws co.edu.corhuila.csp.booking.domain.model.InvalidStatusTransitionException when the
     *         reservation is expired or its hold time has passed
     */
    Reservation confirmReservationForWorkflow(UUID reservationId, String correlationId);

    /**
     * Releases a HELD reservation of any user for the workflow service, before its deadline (ADR-035):
     * it becomes EXPIRED, its seats are available again and {@code ReservationExpired} is written.
     * Idempotent by state: an already EXPIRED reservation is returned and nothing is written.
     *
     * @return the EXPIRED reservation
     * @throws co.edu.corhuila.csp.booking.domain.model.ReservationNotFoundException when no reservation has the id
     * @throws co.edu.corhuila.csp.booking.domain.model.InvalidStatusTransitionException when the
     *         reservation is CONFIRMED, which is never cancelled in the MVP
     */
    Reservation releaseReservationForWorkflow(UUID reservationId, String correlationId);

    /** The page of the reservations of the caller, in the window and filters of the request. */
    ReservationPage listReservations(UUID callerId, ReservationQuery query);

    /**
     * Expires HELD reservations past their hold time (HU-BOOKING-002). Called by csp-worker through
     * the internal maintenance endpoint, never by a client.
     *
     * @param correlationId the correlation id of the sweep run, written to every outbox event
     * @return how many reservations were expired in this run
     */
    ExpireHoldsResult expireHolds(int batchSize, String correlationId);
}
