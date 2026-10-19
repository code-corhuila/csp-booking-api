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
 * Body of {@code POST /internal/workflow/holds}: the body of {@code POST /holds} plus the user the
 * hold is for, which the workflow took from the token it validated (ADR-035). The bounds are the
 * ones of {@link CreateHoldRequest}.
 */
public record WorkflowCreateHoldRequest(
        @NotNull UUID userId,
        @NotNull UUID showtimeId,
        @NotEmpty @UniqueElements List<@NotBlank @Size(max = 10) String> seatLabels,
        @NotBlank @Size(max = 255) String movieTitle,
        @NotBlank @Size(max = 255) String roomName,
        @Min(60) @Max(900) Integer holdDurationSeconds) {

    /** The seconds this hold lasts: the value of the body, or the default of the contract. */
    public int effectiveDurationSeconds() {
        return holdDurationSeconds == null ? CreateHoldRequest.DEFAULT_DURATION_SECONDS : holdDurationSeconds;
    }
}
