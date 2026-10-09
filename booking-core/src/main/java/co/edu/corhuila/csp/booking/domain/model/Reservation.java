package co.edu.corhuila.csp.booking.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * One reservation lifecycle: the seats held for a showtime and what happens to them.
 * It is immutable: every transition returns a new instance, so a failed rule changes nothing.
 * Money is an integer in cents.
 *
 * <p>Two kinds of failure, on purpose: a malformed object throws {@link InvalidReservationException}
 * from the constructor, while a rule evaluated against the state ({@link #hold}'s duration,
 * {@link #confirm}, {@link #expire}, {@link #requireOwner}) throws its own typed rule exception.
 */
public record Reservation(
        UUID id,
        UUID userId,
        UUID showtimeId,
        List<String> seatLabels,
        ReservationStatus status,
        Instant expiresAt,
        String movieTitleSnapshot,
        String roomNameSnapshot,
        long totalAmount,
        Instant createdAt,
        Instant confirmedAt) {

    public static final Duration MIN_HOLD = Duration.ofSeconds(60);
    public static final Duration MAX_HOLD = Duration.ofSeconds(900);
    public static final Duration DEFAULT_HOLD = Duration.ofSeconds(600);

    public Reservation {
        if (id == null || userId == null || showtimeId == null || status == null
                || expiresAt == null || createdAt == null) {
            throw new InvalidReservationException("a reservation needs an id, a user, a showtime, a status and its dates");
        }
        if (seatLabels == null || seatLabels.isEmpty()) {
            throw new InvalidReservationException("a reservation needs at least one seat");
        }
        if (seatLabels.stream().anyMatch(label -> label == null || label.isBlank())
                || new HashSet<>(seatLabels).size() != seatLabels.size()) {
            throw new InvalidReservationException("seat labels must be non blank and not repeated");
        }
        if (movieTitleSnapshot == null || movieTitleSnapshot.isBlank()
                || roomNameSnapshot == null || roomNameSnapshot.isBlank()) {
            throw new InvalidReservationException("the title and room snapshots are required");
        }
        if (totalAmount < 0) {
            throw new InvalidReservationException("the total amount cannot be negative");
        }
        seatLabels = List.copyOf(seatLabels);
    }

    /** Creates a HELD reservation that expires {@code duration} after {@code now}. */
    public static Reservation hold(UUID id, UUID userId, UUID showtimeId, List<String> seatLabels, Duration duration,
                                   Instant now, String movieTitleSnapshot, String roomNameSnapshot, long totalAmount) {
        if (duration.compareTo(MIN_HOLD) < 0 || duration.compareTo(MAX_HOLD) > 0) {
            throw new BusinessRuleViolationException("the hold lasts between 60 and 900 seconds");
        }
        return new Reservation(id, userId, showtimeId, seatLabels, ReservationStatus.HELD, now.plus(duration),
                movieTitleSnapshot, roomNameSnapshot, totalAmount, now, null);
    }

    /** True when the hold time has run out at {@code now}, whether or not it was already marked EXPIRED. */
    public boolean isOverdueAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** HELD and not overdue becomes CONFIRMED. An expired or confirmed reservation cannot be confirmed. */
    public Reservation confirm(Instant now) {
        if (status != ReservationStatus.HELD || isOverdueAt(now)) {
            throw new InvalidStatusTransitionException("the reservation cannot be confirmed from its current status");
        }
        return new Reservation(id, userId, showtimeId, seatLabels, ReservationStatus.CONFIRMED, expiresAt,
                movieTitleSnapshot, roomNameSnapshot, totalAmount, createdAt, now);
    }

    /** HELD and overdue becomes EXPIRED. Anything else cannot expire. */
    public Reservation expire(Instant now) {
        if (status != ReservationStatus.HELD || !isOverdueAt(now)) {
            throw new InvalidStatusTransitionException("only an overdue HELD reservation can expire");
        }
        return new Reservation(id, userId, showtimeId, seatLabels, ReservationStatus.EXPIRED, expiresAt,
                movieTitleSnapshot, roomNameSnapshot, totalAmount, createdAt, confirmedAt);
    }

    /** The reservation can only be read by the user who owns it. */
    public void requireOwner(UUID caller) {
        if (!userId.equals(caller)) {
            throw new ReservationAccessDeniedException(id, caller);
        }
    }
}
