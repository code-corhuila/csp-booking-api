package co.edu.corhuila.csp.booking.application.port.out;

import co.edu.corhuila.csp.booking.domain.model.Reservation;
import java.util.Objects;

/**
 * Everything the durable side needs for one hold request: the aggregate, the key that makes the
 * call repeatable (Norma 5.3.8), the hash that tells a replay from a conflicting payload, and the
 * correlation id carried in the metadata of the outbox event.
 */
public record CreateHoldCommand(Reservation reservation, String idempotencyKey, String requestHash,
        String correlationId) {

    public CreateHoldCommand {
        Objects.requireNonNull(reservation, "the reservation is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("the idempotency key is required");
        }
        if (requestHash == null || requestHash.isBlank()) {
            throw new IllegalArgumentException("the request hash is required");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("the correlation id is required");
        }
    }
}
