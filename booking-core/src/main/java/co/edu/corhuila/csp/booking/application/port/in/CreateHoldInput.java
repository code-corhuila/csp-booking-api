package co.edu.corhuila.csp.booking.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One hold request as the transport delivers it: the identity comes from the token, the seats and
 * the snapshots from the body of the contract, and the key and the correlation id from the headers
 * (Norma 5.3.8).
 */
public record CreateHoldInput(UUID userId, UUID showtimeId, List<String> seatLabels, String movieTitle,
        String roomName, int holdDurationSeconds, String idempotencyKey, String correlationId) {

    public CreateHoldInput {
        Objects.requireNonNull(userId, "the user of the token is required");
        Objects.requireNonNull(showtimeId, "the showtime is required");
        Objects.requireNonNull(seatLabels, "the seats are required");
        Objects.requireNonNull(idempotencyKey, "the idempotency key is required");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("the idempotency key cannot be blank");
        }
        seatLabels = List.copyOf(seatLabels);
    }
}
