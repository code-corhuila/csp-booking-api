package co.edu.corhuila.csp.booking.adapter.in.http;

import co.edu.corhuila.csp.booking.domain.model.Reservation;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One reservation, in the order and with the names of the {@code Reservation} schema. A
 * confirmation that has not happened is left out of the answer instead of being sent as null:
 * the schema does not require the field (it declares it nullable).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReservationResponse(UUID id, UUID userId, UUID showtimeId, List<String> seatLabels, String status,
        Instant expiresAt, String movieTitleSnapshot, String roomNameSnapshot, long totalAmount, Instant createdAt,
        Instant confirmedAt) {

    public static ReservationResponse of(Reservation reservation) {
        return new ReservationResponse(
                reservation.id(),
                reservation.userId(),
                reservation.showtimeId(),
                reservation.seatLabels(),
                reservation.status().name(),
                reservation.expiresAt(),
                reservation.movieTitleSnapshot(),
                reservation.roomNameSnapshot(),
                reservation.totalAmount(),
                reservation.createdAt(),
                reservation.confirmedAt());
    }
}
