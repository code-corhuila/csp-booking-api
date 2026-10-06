package co.edu.corhuila.csp.booking.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.booking.application.port.out.CreateHoldCommand;
import co.edu.corhuila.csp.booking.application.port.out.CreateHoldResult;
import co.edu.corhuila.csp.booking.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.booking.application.port.out.ReservationPage;
import co.edu.corhuila.csp.booking.application.port.out.ReservationQuery;
import co.edu.corhuila.csp.booking.domain.model.BusinessRuleViolationException;
import co.edu.corhuila.csp.booking.domain.model.Reservation;
import co.edu.corhuila.csp.booking.domain.model.ReservationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The SQL of the hold against the real engine, with the schema of csp-booking-db (Annex C).
 * It is skipped when TEST_DATABASE_URL is not defined: the URL carries the credentials, so one
 * variable is enough, for example {@code jdbc:postgresql://localhost:5432/csp} with the user and
 * the password in the environment, never in the source.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcHoldRepositoryTest {

    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";
    private static final String KEY = "11111111-2222-3333-4444-555555555555";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcHoldRepository repository;

    private final List<UUID> writtenHolds = new ArrayList<>();

    @BeforeAll
    static void openPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("TEST_DATABASE_URL"));
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcHoldRepository(jdbc, new DataSourceTransactionManager(dataSource), new ObjectMapper());
    }

    @AfterEach
    void removeWhatThisTestWrote() {
        for (UUID holdId : writtenHolds) {
            jdbc.update("DELETE FROM booking.outbox_event WHERE aggregate_id = ?", holdId);
            jdbc.update("DELETE FROM booking.reservation WHERE hold_id = ?", holdId);
            jdbc.update("DELETE FROM booking.seat_hold WHERE id = ?", holdId);
        }
        writtenHolds.clear();
    }

    @Test
    void aCreatedHoldWritesFiveTablesAndExactlyOneEvent() throws Exception {
        Reservation reservation = hold(seats("A1", "A2"));
        CreateHoldResult result = repository.create(command(reservation, KEY, "hash-one"));

        assertInstanceOf(CreateHoldResult.Created.class, result);
        assertEquals(reservation, result.reservation());

        assertEquals(1, count("SELECT count(*) FROM booking.seat_hold WHERE id = ? AND status = 'HELD'", reservation.id()));
        assertEquals("Movie", scalar("SELECT movie_title_snapshot FROM booking.seat_hold WHERE id = ?", reservation.id()));
        assertEquals("Room 1", scalar("SELECT room_name_snapshot FROM booking.seat_hold WHERE id = ?", reservation.id()));
        OffsetDateTime storedExpiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM booking.seat_hold WHERE id = ?", OffsetDateTime.class, reservation.id());
        assertEquals(reservation.expiresAt(), storedExpiresAt.toInstant());

        assertEquals(2, count("SELECT count(*) FROM booking.seat_hold_item WHERE hold_id = ?", reservation.id()));
        assertEquals(1, count("SELECT count(*) FROM booking.reservation WHERE id = ? AND hold_id = ? AND total_amount = 0",
                reservation.id(), reservation.id()));
        assertEquals(2, count("SELECT count(*) FROM booking.reservation_seat WHERE reservation_id = ?", reservation.id()));
        assertEquals("hash-one", scalar("SELECT request_hash FROM booking.idempotency_key WHERE hold_id = ?", reservation.id()));

        assertEquals(1, count("SELECT count(*) FROM booking.outbox_event WHERE aggregate_id = ? AND event_type = 'ReservationHeld'",
                reservation.id()));
        // jsonb normalizes spaces and the order of the keys: the document is read, not matched as text.
        JsonNode event = MAPPER.readTree(
                scalar("SELECT payload::text FROM booking.outbox_event WHERE aggregate_id = ?", reservation.id()));
        assertEquals(scalar("SELECT id::text FROM booking.outbox_event WHERE aggregate_id = ?", reservation.id()),
                event.path("eventId").asText());
        assertEquals("ReservationHeld", event.path("eventType").asText());
        assertEquals("Reservation", event.path("aggregateType").asText());
        assertEquals("booking-service", event.path("source").asText());
        assertEquals(reservation.id().toString(), event.path("aggregateId").asText());
        assertEquals(CORRELATION, event.path("metadata").path("correlationId").asText());
        assertTrue(event.path("metadata").path("causationId").isNull());
        assertEquals(reservation.id().toString(), event.path("payload").path("reservationId").asText());
        assertEquals(reservation.userId().toString(), event.path("payload").path("userId").asText());
        List<String> publishedSeats = new ArrayList<>();
        event.path("payload").path("seatNumbers").forEach(seat -> publishedSeats.add(seat.asText()));
        assertEquals(List.of("A1", "A2"), publishedSeats);
    }

    @Test
    void theSameKeyWithTheSamePayloadReturnsTheOriginalAndCreatesNothing() {
        Reservation first = hold(seats("B1"));
        Reservation retry = hold(seats("B1"));

        assertInstanceOf(CreateHoldResult.Created.class, repository.create(command(first, KEY, "hash-same")));
        CreateHoldResult second = repository.create(command(retry, KEY, "hash-same"));

        CreateHoldResult.Replayed replay = assertInstanceOf(CreateHoldResult.Replayed.class, second);
        assertEquals(first, replay.reservation());
        assertEquals(1, count("SELECT count(*) FROM booking.seat_hold WHERE id IN (?, ?)", first.id(), retry.id()));
        assertEquals(1, count("SELECT count(*) FROM booking.outbox_event WHERE aggregate_id IN (?, ?)",
                first.id(), retry.id()));
        assertEquals(0, count("SELECT count(*) FROM booking.reservation WHERE hold_id = ?", retry.id()));
    }

    @Test
    void theSameKeyWithAnotherPayloadIsRejectedAndLeavesNothingBehind() {
        Reservation first = hold(seats("C1"));
        Reservation other = hold(seats("C1"), "Another Movie", "Room 2");

        repository.create(command(first, KEY, "hash-original"));
        assertThrows(IdempotencyKeyConflictException.class, () -> repository.create(command(other, KEY, "hash-other")));

        assertEquals(0, count("SELECT count(*) FROM booking.seat_hold WHERE id = ?", other.id()));
        assertEquals(0, count("SELECT count(*) FROM booking.idempotency_key WHERE hold_id = ?", other.id()));
        assertEquals(0, count("SELECT count(*) FROM booking.outbox_event WHERE aggregate_id = ?", other.id()));
        assertEquals(1, count("SELECT count(*) FROM booking.reservation WHERE hold_id = ?", first.id()));
    }

    @Test
    void aSeatThatIsAlreadyHeldIsRefusedWithoutConsumingTheKey() {
        UUID showtime = UUID.randomUUID();
        Reservation taken = hold(showtime, seats("D1"), "Movie", "Room 1");
        Reservation attempt = hold(showtime, seats("D1"), "Movie", "Room 1");
        repository.create(command(taken, "aaaaaaaa-0000-0000-0000-000000000001", "hash-first"));

        BusinessRuleViolationException exception = assertThrows(BusinessRuleViolationException.class,
                () -> repository.create(command(attempt, "aaaaaaaa-0000-0000-0000-000000000002", "hash-second")));

        assertTrue(exception.getMessage().contains("already HELD"), exception.getMessage());
        assertEquals(0, count("SELECT count(*) FROM booking.seat_hold WHERE id = ?", attempt.id()));
        assertEquals(0, count("SELECT count(*) FROM booking.idempotency_key WHERE hold_id = ?", attempt.id()));
        assertEquals(0, count("SELECT count(*) FROM booking.outbox_event WHERE aggregate_id = ?", attempt.id()));
        assertEquals(1, count("SELECT count(*) FROM booking.seat_hold_item WHERE showtime_id = ? AND seat_number = 'D1'",
                taken.showtimeId()));
    }

    @Test
    void findByIdAnswersWithTheReservationOfAnyOwnerOrWithNothing() {
        Reservation reservation = hold(seats("E1"));
        assertInstanceOf(CreateHoldResult.Created.class, repository.create(command(reservation, newKey(), "hash-e")));

        assertEquals(reservation, repository.findById(reservation.id()).orElseThrow());
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByUserKeepsOnlyThatUserNewestFirstAndPaginates() {
        UUID user = UUID.randomUUID();
        for (String seat : List.of("F1", "F2", "F3")) {
            repository.create(command(hold(user, seats(seat)), newKey(), "hash-" + seat));
        }
        repository.create(command(hold(seats("F1")), newKey(), "hash-stranger"));

        ReservationPage all = repository.findByUser(user, new ReservationQuery(1, 10, null, null));
        assertEquals(3, all.total());
        assertEquals(3, all.items().size());
        assertTrue(all.items().stream().allMatch(item -> item.userId().equals(user)));
        List<Instant> newestFirst = all.items().stream().map(Reservation::createdAt).toList();
        assertEquals(newestFirst, newestFirst.stream().sorted(Comparator.reverseOrder()).toList());

        ReservationPage firstPage = repository.findByUser(user, new ReservationQuery(1, 2, null, null));
        ReservationPage secondPage = repository.findByUser(user, new ReservationQuery(2, 2, null, null));
        assertEquals(3, firstPage.total());
        assertEquals(2, firstPage.items().size());
        assertEquals(1, secondPage.items().size());
    }

    @Test
    void findByUserFiltersByStatusAndKeepsOnlyWhatWasCreatedBeforeTheInstant() {
        UUID user = UUID.randomUUID();
        Reservation held = hold(user, seats("G1"));
        assertInstanceOf(CreateHoldResult.Created.class, repository.create(command(held, newKey(), "hash-g")));

        assertEquals(0,
                repository.findByUser(user, new ReservationQuery(1, 10, ReservationStatus.CONFIRMED, null)).total());
        assertEquals(1, repository.findByUser(user, new ReservationQuery(1, 10, ReservationStatus.HELD, null)).total());
        assertEquals(0,
                repository.findByUser(user, new ReservationQuery(1, 10, null, held.createdAt().minusSeconds(1)))
                        .total());
        assertEquals(1,
                repository.findByUser(user, new ReservationQuery(1, 10, null, held.createdAt().plusSeconds(1)))
                        .total());
    }

    private Reservation hold(List<String> seats) {
        return hold(UUID.randomUUID(), UUID.randomUUID(), seats, "Movie", "Room 1");
    }

    private Reservation hold(List<String> seats, String movieTitle, String roomName) {
        return hold(UUID.randomUUID(), UUID.randomUUID(), seats, movieTitle, roomName);
    }

    private Reservation hold(UUID userId, List<String> seats) {
        return hold(userId, UUID.randomUUID(), seats, "Movie", "Room 1");
    }

    private Reservation hold(UUID showtimeId, List<String> seats, String movieTitle, String roomName) {
        return hold(UUID.randomUUID(), showtimeId, seats, movieTitle, roomName);
    }

    private Reservation hold(UUID userId, UUID showtimeId, List<String> seats, String movieTitle, String roomName) {
        // PostgreSQL keeps microseconds: an instant with nanoseconds would not round-trip.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Reservation reservation = Reservation.hold(UUID.randomUUID(), userId, showtimeId, seats,
                Duration.ofSeconds(600), now, movieTitle, roomName, 0);
        writtenHolds.add(reservation.id());
        return reservation;
    }

    private static List<String> seats(String... labels) {
        return List.of(labels);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static CreateHoldCommand command(Reservation reservation, String key, String requestHash) {
        return new CreateHoldCommand(reservation, key, requestHash, CORRELATION);
    }

    private static int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static String scalar(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }
}
