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
     * Makes the confirmation durable: the reservation, its hold and every seat become CONFIRMED and
     * the {@code BookingConfirmed} outbox event is written, all in one transaction (Norma 5.3.11).
     * A confirmed seat stays inside {@code uk_seat_hold_item_active_seat}, so it can never be held again.
     *
     * <p>The statements keep a {@code status = 'HELD'} guard and the hold must not be past
     * {@code now}, so a sweep that wins the race is never overwritten and the event is written
     * exactly once.
     *
     * @param confirmed the reservation already transitioned by the domain to CONFIRMED
     * @param now the instant the confirmation was decided on
     * @param correlationId the correlation id of the request, written to the event metadata
     * @throws co.edu.corhuila.csp.booking.domain.model.InvalidStatusTransitionException when the
     *         reservation was no longer a HELD reservation inside its hold time
     */
    void confirm(Reservation confirmed, Instant now, String correlationId);

    /**
     * HELD reservations past their expiration time, oldest expiration first, up to {@code limit}.
     * Used by the expiration sweep of csp-worker (HU-BOOKING-002).
     */
    List<Reservation> findOverdueHeld(Instant now, int limit);

    /**
     * Releases an expired hold: the reservation and its hold become EXPIRED and the held seats
     * become RELEASED, which is what makes them available for new holds (the partial unique index
     * {@code uk_seat_hold_item_active_seat} only covers HELD and CONFIRMED seats). All of it and the
     * {@code ReservationExpired} outbox event happen in one transaction (Norma 5.3.11).
     *
     * <p>The statements keep a {@code status = 'HELD'} guard so a concurrent confirmation that wins
     * the race is never overwritten, and the reservation is only expired when its hold really is
     * past {@code now}, so the invariant is enforced by the engine and not only by the caller.
     *
     * @param expired the reservation already transitioned by the domain to EXPIRED
     * @param now the instant the sweep decided on
     * @param correlationId the correlation id of the sweep run, written to the event metadata
     * @throws HoldNoLongerOverdueException when the hold was no longer HELD at {@code now}
     */
    void expire(Reservation expired, Instant now, String correlationId);
}
