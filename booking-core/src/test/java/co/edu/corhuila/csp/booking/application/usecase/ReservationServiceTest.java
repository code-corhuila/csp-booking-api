package co.edu.corhuila.csp.booking.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.csp.booking.application.port.in.CreateHoldInput;
import co.edu.corhuila.csp.booking.application.port.out.CreateHoldCommand;
import co.edu.corhuila.csp.booking.application.port.out.CreateHoldResult;
import co.edu.corhuila.csp.booking.application.port.out.HoldRepository;
import co.edu.corhuila.csp.booking.application.port.out.ReservationPage;
import co.edu.corhuila.csp.booking.application.port.out.ReservationQuery;
import co.edu.corhuila.csp.booking.domain.model.BusinessRuleViolationException;
import co.edu.corhuila.csp.booking.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.booking.domain.model.Reservation;
import co.edu.corhuila.csp.booking.domain.model.ReservationAccessDeniedException;
import co.edu.corhuila.csp.booking.domain.model.ReservationNotFoundException;
import co.edu.corhuila.csp.booking.domain.model.ReservationStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The use cases of the hold, with fakes of the output port (Annex C). */
class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:15:30.123456Z");
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SHOWTIME = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String KEY = "44444444-4444-4444-4444-444444444444";
    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";

    private final FakeHolds holds = new FakeHolds();
    private final ReservationService service = new ReservationService(holds, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aHoldStartsAtTheClockWithTheSnapshotOfTheRequestAndNoAmount() {
        CreateHoldResult result = service.createHold(input());

        assertInstanceOf(CreateHoldResult.Created.class, result);
        Reservation reservation = result.reservation();
        assertEquals(ReservationStatus.HELD, reservation.status());
        assertEquals(USER, reservation.userId());
        assertEquals(SHOWTIME, reservation.showtimeId());
        assertEquals(List.of("A1", "A2"), reservation.seatLabels());
        assertEquals(NOW, reservation.createdAt());
        assertEquals(NOW.plusSeconds(600), reservation.expiresAt());
        assertEquals("Movie", reservation.movieTitleSnapshot());
        assertEquals("Room 1", reservation.roomNameSnapshot());
        assertEquals(0, reservation.totalAmount());
        assertNull(reservation.confirmedAt());

        assertEquals(KEY, holds.stored.idempotencyKey());
        assertEquals(CORRELATION, holds.stored.correlationId());
        assertEquals(64, holds.stored.requestHash().length());
    }

    @Test
    void theHoldStartsAtThePrecisionThePlatformStoresSoThatAReplayMatchesTheFirstAnswer() {
        ReservationService withNanoseconds = new ReservationService(holds, Clock.fixed(NOW.plusNanos(123),
                ZoneOffset.UTC));

        withNanoseconds.createHold(input());

        Instant created = holds.stored.reservation().createdAt();
        assertEquals(NOW, created);
        assertEquals(0, created.getNano() % 1_000);
    }

    @Test
    void theHashIsStableForTheSameRequestAndBelongsToItsUser() {
        String hash = hashOf(input());

        assertEquals(hash, hashOf(input()));
        assertEquals(hash, hashOf(withSeatsInAnotherOrder()));
        assertNotEquals(hash, hashOf(withAnotherTitle()));
        assertNotEquals(hash, hashOf(anotherUser()));
    }

    @Test
    void aReservationIsReadByItsIdForItsOwner() {
        Reservation reservation = reservationOf(USER);
        holds.found = Optional.of(reservation);

        assertSame(reservation, service.getReservation(USER, reservation.id()));
        assertEquals(reservation.id(), holds.askedById);
    }

    @Test
    void aReservationOfAnotherUserIsRefused() {
        holds.found = Optional.of(reservationOf(USER));

        assertThrows(ReservationAccessDeniedException.class, () -> service.getReservation(OTHER, UUID.randomUUID()));
    }

    @Test
    void anUnknownReservationIsNotFound() {
        holds.found = Optional.empty();

        assertThrows(ReservationNotFoundException.class, () -> service.getReservation(USER, UUID.randomUUID()));
    }

    @Test
    void thePageIsAskedForTheCallerWithItsFilters() {
        ReservationQuery query = new ReservationQuery(2, 10, ReservationStatus.HELD, NOW);
        ReservationPage page = new ReservationPage(List.of(reservationOf(USER)), 1);
        holds.page = page;

        assertSame(page, service.listReservations(USER, query));
        assertEquals(USER, holds.askedForUser);
        assertEquals(query, holds.askedWith);
    }

    @Test
    void aDurationTheDomainForbidsNeverReachesTheRepository() {
        CreateHoldInput tooShort = new CreateHoldInput(USER, SHOWTIME, List.of("A1"), "Movie", "Room 1", 30, KEY,
                CORRELATION);

        assertThrows(BusinessRuleViolationException.class, () -> service.createHold(tooShort));
        assertNull(holds.stored);
    }

    @Test
    void theExpirationSweepQueriesOverdueHoldsAndExpiresThemWithTheCorrelationId() {
        Reservation overdue = Reservation.hold(UUID.randomUUID(), USER, SHOWTIME, List.of("A1"),
                Duration.ofSeconds(600), NOW.minusSeconds(700), "Movie", "Room 1", 0);
        holds.overdue = List.of(overdue);

        var result = service.expireHolds(100, CORRELATION);

        assertEquals(1, result.expired());
        assertEquals(0, result.remaining());
        assertEquals(CORRELATION, holds.expiredCorrelation);
        // The domain returns a new aggregate in EXPIRED: the same id, the same seats, and the
        // instant the sweep decided on. What reaches the repository is the transition, never the
        // stored HELD instance.
        Reservation expired = holds.expired.get(0);
        assertEquals(overdue.id(), expired.id());
        assertEquals(ReservationStatus.EXPIRED, expired.status());
        assertEquals(overdue.seatLabels(), expired.seatLabels());
    }

    @Test
    void aHoldThatIsNotOverdueIsRefusedByTheDomainAndNeverReachesTheEngine() {
        Reservation notYetOverdue = Reservation.hold(UUID.randomUUID(), USER, SHOWTIME, List.of("A1"),
                Duration.ofSeconds(600), NOW.minusSeconds(10), "Movie", "Room 1", 0);
        holds.overdue = List.of(notYetOverdue);

        assertThrows(InvalidStatusTransitionException.class, () -> service.expireHolds(100, CORRELATION));

        assertNull(holds.expired);
    }

    @Test
    void theExpirationSweepReportsRemainingOverdueForTheNextRun() {
        Reservation overdue1 = Reservation.hold(UUID.randomUUID(), USER, SHOWTIME, List.of("A1"),
                Duration.ofSeconds(600), NOW.minusSeconds(700), "Movie", "Room 1", 0);
        Reservation overdue2 = Reservation.hold(UUID.randomUUID(), USER, SHOWTIME, List.of("B1"),
                Duration.ofSeconds(600), NOW.minusSeconds(800), "Movie", "Room 1", 0);
        holds.overdue = List.of(overdue1, overdue2);

        var result = service.expireHolds(1, CORRELATION);

        assertEquals(1, result.expired());
        assertEquals(1, result.remaining());
    }

    @Test
    void theExpirationSweepWithNoOverdueHoldsDoesNothing() {
        holds.overdue = List.of();

        var result = service.expireHolds(100, CORRELATION);

        assertEquals(0, result.expired());
        assertEquals(0, result.remaining());
        assertNull(holds.expired);
    }

    private String hashOf(CreateHoldInput request) {
        holds.stored = null;
        service.createHold(request);
        return holds.stored.requestHash();
    }

    private static CreateHoldInput input() {
        return new CreateHoldInput(USER, SHOWTIME, List.of("A1", "A2"), "Movie", "Room 1", 600, KEY, CORRELATION);
    }

    private static CreateHoldInput withSeatsInAnotherOrder() {
        return new CreateHoldInput(USER, SHOWTIME, List.of("A2", "A1"), "Movie", "Room 1", 600, KEY, CORRELATION);
    }

    private static CreateHoldInput withAnotherTitle() {
        return new CreateHoldInput(USER, SHOWTIME, List.of("A1", "A2"), "Another Movie", "Room 1", 600, KEY,
                CORRELATION);
    }

    private static CreateHoldInput anotherUser() {
        return new CreateHoldInput(OTHER, SHOWTIME, List.of("A1", "A2"), "Movie", "Room 1", 600, KEY, CORRELATION);
    }

    private static Reservation reservationOf(UUID userId) {
        return Reservation.hold(UUID.randomUUID(), userId, SHOWTIME, List.of("A1"), Duration.ofSeconds(600), NOW,
                "Movie", "Room 1", 0);
    }

    private static final class FakeHolds implements HoldRepository {

        private CreateHoldCommand stored;
        private Optional<Reservation> found = Optional.empty();
        private ReservationPage page = new ReservationPage(List.of(), 0);
        private UUID askedById;
        private UUID askedForUser;
        private ReservationQuery askedWith;
        private List<Reservation> overdue = List.of();
        private List<Reservation> expired;
        private String expiredCorrelation;

        @Override
        public CreateHoldResult create(CreateHoldCommand command) {
            stored = command;
            return new CreateHoldResult.Created(command.reservation());
        }

        @Override
        public Optional<Reservation> findById(UUID reservationId) {
            askedById = reservationId;
            return found;
        }

        @Override
        public ReservationPage findByUser(UUID userId, ReservationQuery query) {
            askedForUser = userId;
            askedWith = query;
            return page;
        }

        @Override
        public List<Reservation> findOverdueHeld(Instant now, int limit) {
            return overdue.stream().limit(limit).toList();
        }

        @Override
        public void expire(Reservation expiredReservation, Instant now, String correlationId) {
            if (expired == null) {
                expired = new ArrayList<>();
            }
            expired.add(expiredReservation);
            expiredCorrelation = correlationId;
            // An expired reservation is no longer overdue: the sweep must not see it twice. It is
            // matched by id because the domain returns a new instance in EXPIRED.
            overdue = overdue.stream()
                    .filter(candidate -> !candidate.id().equals(expiredReservation.id()))
                    .toList();
        }
    }
}
