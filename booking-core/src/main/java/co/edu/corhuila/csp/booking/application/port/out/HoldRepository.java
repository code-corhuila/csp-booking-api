package co.edu.corhuila.csp.booking.application.port.out;

import co.edu.corhuila.csp.booking.domain.model.Reservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable side of the hold. Every write of one call happens in one database transaction
 * (Norma 5.3.8 and 5.3.11): the hold, its seats, the idempotency key and the outbox event are
 * stored together or not at all.
 */
public interface HoldRepository {

    /**
     * Stores the reservation of a hold request.
     *
     * @return {@link CreateHoldResult.Created} when this call made the hold, or
     *         {@link CreateHoldResult.Replayed} when the key was already used with the same
     *         payload and the original reservation is returned without creating anything
     * @throws IdempotencyKeyConflictException when the key was already used with another payload
     * @throws co.edu.corhuila.csp.booking.domain.model.BusinessRuleViolationException when one of
     *         the seats is already held for that showtime
     */
    CreateHoldResult create(CreateHoldCommand command);

    /**
     * The reservation with that id, of any owner: whether it exists and who owns it are two
     * different answers for the caller (404 and 403), so the read does not filter by user.
     */
    Optional<Reservation> findById(UUID reservationId);

    /**
     * The reservations of one user, from the newest createdAt to the oldest with the id as
     * tiebreaker, in the window the contract asks for.
     */
    ReservationPage findByUser(UUID userId, ReservationQuery query);

    /**
     * HELD reservations past their expiration time, oldest expiration first, up to {@code limit}.
     * Used by the expiration sweep of csp-worker (HU-BOOKING-002).
     */
    List<Reservation> findOverdueHeld(Instant now, int limit);

    /**
     * Transitions a reservation to EXPIRED and writes the {@code ReservationExpired} outbox event
     * in the same transaction (Norma 5.3.11). The correlation id of the sweep run travels in the
     * event metadata.
     */
    void expire(Reservation reservation, String correlationId);
}
