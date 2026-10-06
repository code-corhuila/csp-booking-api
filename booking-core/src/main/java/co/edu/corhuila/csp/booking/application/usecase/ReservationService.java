package co.edu.corhuila.csp.booking.application.usecase;

import co.edu.corhuila.csp.booking.application.port.in.CreateHoldInput;
import co.edu.corhuila.csp.booking.application.port.in.ReservationUseCases;
import co.edu.corhuila.csp.booking.application.port.out.CreateHoldCommand;
import co.edu.corhuila.csp.booking.application.port.out.CreateHoldResult;
import co.edu.corhuila.csp.booking.application.port.out.ExpireHoldsResult;
import co.edu.corhuila.csp.booking.application.port.out.HoldNoLongerOverdueException;
import co.edu.corhuila.csp.booking.application.port.out.HoldRepository;
import co.edu.corhuila.csp.booking.application.port.out.ReservationPage;
import co.edu.corhuila.csp.booking.application.port.out.ReservationQuery;
import co.edu.corhuila.csp.booking.domain.model.Reservation;
import co.edu.corhuila.csp.booking.domain.model.ReservationNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The use cases of the reservation aggregate: the hold, the expiration sweep and the two reads the
 * contract exposes. It depends only on the domain and on the ports, and it asks the clock for the
 * time so the expiry of a hold can be verified without waiting for it.
 */
public class ReservationService implements ReservationUseCases {

    private final HoldRepository holds;
    private final Clock clock;

    public ReservationService(HoldRepository holds, Clock clock) {
        this.holds = holds;
        this.clock = clock;
    }

    @Override
    public CreateHoldResult createHold(CreateHoldInput input) {
        // Cut 2 runs without CATALOG_BASE_URL: the title and the room are the values of the
        // request and the start time of the showtime stays null (ADR-020). The platform stores
        // microseconds, so the hold starts at one: a replay must answer the exact instant the
        // first answer did.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Reservation reservation = Reservation.hold(UUID.randomUUID(), input.userId(), input.showtimeId(),
                input.seatLabels(), Duration.ofSeconds(input.holdDurationSeconds()), now, input.movieTitle(),
                input.roomName(), 0);
        return holds.create(
                new CreateHoldCommand(reservation, input.idempotencyKey(), requestHash(input), input.correlationId()));
    }

    @Override
    public Reservation getReservation(UUID callerId, UUID reservationId) {
        Reservation reservation = holds.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        reservation.requireOwner(callerId);
        return reservation;
    }

    @Override
    public ReservationPage listReservations(UUID callerId, ReservationQuery query) {
        return holds.findByUser(callerId, query);
    }

    @Override
    public ExpireHoldsResult expireHolds(int batchSize, String correlationId) {
        Instant now = clock.instant();
        List<Reservation> overdue = holds.findOverdueHeld(now, batchSize);
        int expired = 0;
        for (Reservation reservation : overdue) {
            // The domain owns the invariant: a hold that is not overdue is refused here and never
            // reaches the engine, so a sweep running on a clock behind the stored expiry changes
            // nothing.
            Reservation transitioned = reservation.expire(now);
            try {
                holds.expire(transitioned, now, correlationId);
                expired++;
            } catch (HoldNoLongerOverdueException raceLost) {
                // A confirmation won the race for this hold: it stays as it is and the rest of
                // the batch goes on, so one lost race never aborts the run.
            }
        }
        int remaining = holds.findOverdueHeld(now, batchSize).size();
        return new ExpireHoldsResult(expired, remaining);
    }

    /**
     * The hash that tells a replay from a conflicting payload (Norma 5.3.8): the same request
     * hashes the same however the seats were ordered, and the user is part of it so a key of one
     * client can never be answered with the reservation of another.
     */
    private static String requestHash(CreateHoldInput input) {
        String canonical = String.join("|",
                input.userId().toString(),
                input.showtimeId().toString(),
                input.seatLabels().stream().sorted().collect(Collectors.joining(",")),
                String.valueOf(input.movieTitle()),
                String.valueOf(input.roomName()),
                Integer.toString(input.holdDurationSeconds()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
