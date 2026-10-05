package co.edu.corhuila.csp.booking.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static Reservation held(List<String> seats, Duration duration) {
        return Reservation.hold(UUID.randomUUID(), USER, UUID.randomUUID(), seats, duration, NOW, "Movie", "Room 1", 2500);
    }

    @Test
    void holdStartsHeldAndExpiresAfterTheDuration() {
        Reservation reservation = held(List.of("A1", "A2"), Reservation.DEFAULT_HOLD);

        assertEquals(ReservationStatus.HELD, reservation.status());
        assertEquals(NOW.plusSeconds(600), reservation.expiresAt());
        assertEquals(NOW, reservation.createdAt());
        assertNull(reservation.confirmedAt());
    }

    @Test
    void holdDurationIsBetween60And900Seconds() {
        assertThrows(BusinessRuleViolationException.class, () -> held(List.of("A1"), Duration.ofSeconds(59)));
        assertThrows(BusinessRuleViolationException.class, () -> held(List.of("A1"), Duration.ofSeconds(901)));
        held(List.of("A1"), Duration.ofSeconds(60));
        held(List.of("A1"), Duration.ofSeconds(900));
    }

    @Test
    void seatsAreRequiredNotBlankAndNotRepeated() {
        assertThrows(BusinessRuleViolationException.class, () -> held(List.of(), Reservation.DEFAULT_HOLD));
        assertThrows(BusinessRuleViolationException.class, () -> held(List.of(" "), Reservation.DEFAULT_HOLD));
        assertThrows(BusinessRuleViolationException.class, () -> held(List.of("A1", "A1"), Reservation.DEFAULT_HOLD));
    }

    @Test
    void theAmountCannotBeNegative() {
        assertThrows(BusinessRuleViolationException.class, () -> Reservation.hold(UUID.randomUUID(), USER,
                UUID.randomUUID(), List.of("A1"), Reservation.DEFAULT_HOLD, NOW, "Movie", "Room 1", -1));
    }

    @Test
    void confirmBeforeExpirationConfirmsAndKeepsTheOriginal() {
        Reservation original = held(List.of("A1"), Reservation.DEFAULT_HOLD);

        Reservation confirmed = original.confirm(NOW.plusSeconds(599));

        assertEquals(ReservationStatus.CONFIRMED, confirmed.status());
        assertEquals(NOW.plusSeconds(599), confirmed.confirmedAt());
        assertEquals(ReservationStatus.HELD, original.status());
    }

    @Test
    void confirmAtOrAfterExpirationIsRejectedEvenIfNotMarkedExpired() {
        Reservation original = held(List.of("A1"), Reservation.DEFAULT_HOLD);

        assertThrows(InvalidStatusTransitionException.class, () -> original.confirm(NOW.plusSeconds(600)));
    }

    @Test
    void confirmTwiceIsRejected() {
        Reservation confirmed = held(List.of("A1"), Reservation.DEFAULT_HOLD).confirm(NOW.plusSeconds(1));

        assertThrows(InvalidStatusTransitionException.class, () -> confirmed.confirm(NOW.plusSeconds(2)));
    }

    @Test
    void onlyAnOverdueHeldReservationExpires() {
        Reservation original = held(List.of("A1"), Reservation.DEFAULT_HOLD);

        assertFalse(original.isOverdueAt(NOW.plusSeconds(599)));
        assertTrue(original.isOverdueAt(NOW.plusSeconds(600)));
        assertThrows(InvalidStatusTransitionException.class, () -> original.expire(NOW.plusSeconds(599)));

        Reservation expired = original.expire(NOW.plusSeconds(600));
        assertEquals(ReservationStatus.EXPIRED, expired.status());
        assertThrows(InvalidStatusTransitionException.class, () -> expired.expire(NOW.plusSeconds(700)));
        assertThrows(InvalidStatusTransitionException.class, () -> expired.confirm(NOW.plusSeconds(700)));
    }

    @Test
    void aConfirmedReservationNeverExpires() {
        Reservation confirmed = held(List.of("A1"), Reservation.DEFAULT_HOLD).confirm(NOW.plusSeconds(1));

        assertThrows(InvalidStatusTransitionException.class, () -> confirmed.expire(NOW.plusSeconds(700)));
    }

    @Test
    void onlyTheOwnerPassesTheOwnerCheck() {
        Reservation reservation = held(List.of("A1"), Reservation.DEFAULT_HOLD);

        reservation.requireOwner(USER);
        assertThrows(ReservationAccessDeniedException.class, () -> reservation.requireOwner(UUID.randomUUID()));
    }
}
