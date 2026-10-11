package co.edu.corhuila.csp.booking.adapter.in.http;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.hibernate.validator.constraints.UniqueElements;

/**
 * Body of {@code POST /holds} as {@code booking-service.yaml} declares it. Cut 2 runs without
 * {@code CATALOG_BASE_URL}, so the title and the room of the showtime are required here: a missing
 * or empty one is a 400 VALIDATION_ERROR, not a business rule. The lengths are the ones of the
 * columns the migration creates, so a value the database would refuse is named as an invalid field
 * instead of failing inside it (Norma 5.3.10).
 */
public record CreateHoldRequest(
        @NotNull UUID showtimeId,
        @NotEmpty @UniqueElements List<@NotBlank @Size(max = 10) String> seatLabels,
        @NotBlank @Size(max = 255) String movieTitle,
        @NotBlank @Size(max = 255) String roomName,
        @Min(60) @Max(900) Integer holdDurationSeconds) {

    /** The duration the contract gives to the requests that leave it out. */
    private static final int DEFAULT_DURATION_SECONDS = 600;

    /** The seconds this hold lasts: the value of the body, or the default of the contract. */
    public int effectiveDurationSeconds() {
        return holdDurationSeconds == null ? DEFAULT_DURATION_SECONDS : holdDurationSeconds;
    }
}
