package co.edu.corhuila.csp.booking.application.port.out;

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
}
