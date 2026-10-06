package co.edu.corhuila.csp.booking.application.port.out;

import co.edu.corhuila.csp.booking.domain.model.Reservation;
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
}
