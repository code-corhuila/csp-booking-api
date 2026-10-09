package co.edu.corhuila.csp.booking.application.port.out;

import co.edu.corhuila.csp.booking.domain.model.Reservation;

/**
 * Outcome of a hold request: the reservation it belongs to and whether this call created it,
 * because the contract answers 201 when it did and 200 when the key was replayed.
 */
public sealed interface CreateHoldResult {

    Reservation reservation();

    /** The hold did not exist: this call created it. */
    record Created(Reservation reservation) implements CreateHoldResult {
    }

    /** The key was already used with the same payload: the original reservation, untouched. */
    record Replayed(Reservation reservation) implements CreateHoldResult {
    }
}
