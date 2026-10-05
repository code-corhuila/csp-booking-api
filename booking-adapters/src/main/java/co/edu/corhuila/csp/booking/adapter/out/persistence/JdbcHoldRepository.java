package co.edu.corhuila.csp.booking.adapter.out.persistence;

import co.edu.corhuila.csp.booking.application.port.out.CreateHoldCommand;
import co.edu.corhuila.csp.booking.application.port.out.CreateHoldResult;
import co.edu.corhuila.csp.booking.application.port.out.HoldRepository;
import co.edu.corhuila.csp.booking.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.booking.application.port.out.ReservationPage;
import co.edu.corhuila.csp.booking.application.port.out.ReservationQuery;
import co.edu.corhuila.csp.booking.domain.model.BusinessRuleViolationException;
import co.edu.corhuila.csp.booking.domain.model.Reservation;
import co.edu.corhuila.csp.booking.domain.model.ReservationStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SQL of a hold: the hold, its seats, the reservation, its seats, the idempotency key and the
 * outbox event of one request in a single transaction (Norma 5.3.8 and 5.3.11). The database
 * itself refuses a second active hold of the same seat and a second use of the same key, so two
 * concurrent clients cannot double-book or double-charge a retry.
 */
@Repository
public class JdbcHoldRepository implements HoldRepository {

    private static final String AGGREGATE_TYPE = "Reservation";
    private static final String EVENT_TYPE = "ReservationHeld";
    private static final String EXPIRED_EVENT_TYPE = "ReservationExpired";
    private static final String EVENT_SOURCE = "booking-service";

    private static final String INSERT_SEAT_HOLD = """
            INSERT INTO booking.seat_hold
                (id, user_id, showtime_id, status, movie_title_snapshot, room_name_snapshot,
                 showtime_starts_at, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
            """;

    private static final String INSERT_IDEMPOTENCY_KEY = """
            INSERT INTO booking.idempotency_key ("key", hold_id, request_hash)
            VALUES (?, ?, ?)
            ON CONFLICT (key) DO NOTHING
            """;

    private static final String INSERT_HOLD_ITEM = """
            INSERT INTO booking.seat_hold_item (hold_id, showtime_id, seat_number, status)
            VALUES (?, ?, ?, 'HELD')
            """;

    private static final String INSERT_RESERVATION = """
            INSERT INTO booking.reservation
                (id, hold_id, user_id, showtime_id, status, total_amount, created_at, confirmed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_RESERVATION_SEAT = """
            INSERT INTO booking.reservation_seat (reservation_id, seat_number)
            VALUES (?, ?)
            """;

    private static final String INSERT_OUTBOX_EVENT = """
            INSERT INTO booking.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, created_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
            """;

    private static final String FIND_STORED_KEY = """
            SELECT hold_id, request_hash FROM booking.idempotency_key WHERE "key" = ?
            """;

    /**
     * The reservation and its snapshots: one row per seat aggregated into the labels of the hold.
     * Every read composes it with its own filter, order and window.
     */
    private static final String RESERVATION_SELECT = """
            SELECT r.id AS id, r.user_id AS user_id, r.showtime_id AS showtime_id, r.status AS status,
                   r.total_amount AS total_amount, r.created_at AS created_at, r.confirmed_at AS confirmed_at,
                   h.movie_title_snapshot AS movie_title_snapshot, h.room_name_snapshot AS room_name_snapshot,
                   h.expires_at AS expires_at,
                   array_agg(s.seat_number ORDER BY s.seat_number) AS seat_labels
            FROM booking.reservation r
            JOIN booking.seat_hold h ON h.id = r.hold_id
            JOIN booking.reservation_seat s ON s.reservation_id = r.id
            """;

    /** The seats live in their own rows: the aggregate folds them back without a second query. */
    private static final String GROUP_BY_RESERVATION = " GROUP BY r.id, h.id";

    /** The contract orders the list from the newest createdAt to the oldest, id as tiebreaker. */
    private static final String NEWEST_FIRST = " ORDER BY r.created_at DESC, r.id DESC";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public JdbcHoldRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Override
    public CreateHoldResult create(CreateHoldCommand command) {
        return transactionTemplate.execute(status -> insert(command, status));
    }

    /** The reservation of a hold, with its snapshots and its seats in request-independent order. */
    Optional<Reservation> findByHoldId(UUID holdId) {
        return first(RESERVATION_SELECT + "WHERE r.hold_id = ?" + GROUP_BY_RESERVATION, holdId);
    }

    @Override
    public Optional<Reservation> findById(UUID reservationId) {
        return first(RESERVATION_SELECT + "WHERE r.id = ?" + GROUP_BY_RESERVATION, reservationId);
    }

    @Override
    public ReservationPage findByUser(UUID userId, ReservationQuery query) {
        List<Object> arguments = new ArrayList<>();
        String where = whereFor(userId, query, arguments);
        long total = jdbc.queryForObject("SELECT count(*) FROM booking.reservation r " + where, Long.class,
                arguments.toArray());
        arguments.add(query.limit());
        arguments.add(query.offset());
        List<Reservation> items = jdbc.query(
                RESERVATION_SELECT + where + GROUP_BY_RESERVATION + NEWEST_FIRST + " LIMIT ? OFFSET ?",
                this::toReservation, arguments.toArray());
        return new ReservationPage(items, total);
    }

    @Override
    public List<Reservation> findOverdueHeld(Instant now, int limit) {
        return jdbc.query(
                RESERVATION_SELECT + "WHERE r.status = 'HELD' AND h.expires_at < ?"
                        + GROUP_BY_RESERVATION + " ORDER BY h.expires_at ASC LIMIT ?",
                this::toReservation, at(now), limit);
    }

    @Override
    public void expire(Reservation expired, Instant now, String correlationId) {
        String correlation = correlationId != null ? correlationId : UUID.randomUUID().toString();
        transactionTemplate.executeWithoutResult(status -> release(expired, now, correlation));
    }

    /**
     * Releases the hold in one transaction. The three updates are what make the seats available
     * again: the partial unique index {@code uk_seat_hold_item_active_seat} only covers the seats
     * whose status is HELD or CONFIRMED, so a seat only becomes available for a new hold when its
     * row leaves that set. Every statement carries a {@code status = 'HELD'} guard, so a
     * confirmation that won the race is never overwritten and a sweep that runs twice is a no-op.
     */
    private void release(Reservation expired, Instant now, String correlationId) {
        int expiredReservations = jdbc.update("""
                UPDATE booking.reservation r SET status = 'EXPIRED'
                WHERE r.id = ?
                  AND r.status = 'HELD'
                  AND EXISTS (SELECT 1 FROM booking.seat_hold h
                              WHERE h.id = r.hold_id
                                AND h.expires_at <= ?)""",
                expired.id(), at(now));
        if (expiredReservations == 0) {
            throw new IllegalStateException(
                    "the reservation " + expired.id() + " is no longer an overdue HELD reservation");
        }
        jdbc.update("UPDATE booking.seat_hold SET status = 'EXPIRED' WHERE id = ? AND status = 'HELD'",
                expired.id());
        jdbc.update("UPDATE booking.seat_hold_item SET status = 'RELEASED' WHERE hold_id = ? AND status = 'HELD'",
                expired.id());
        insertExpiredOutboxEvent(expired, now, correlationId);
    }

    /**
     * Only literal fragments are appended to the WHERE: every value is bound as a parameter, so no
     * filter can ever become part of the statement.
     */
    private static String whereFor(UUID userId, ReservationQuery query, List<Object> arguments) {
        StringBuilder where = new StringBuilder("WHERE r.user_id = ?");
        arguments.add(userId);
        if (query.status() != null) {
            where.append(" AND r.status = ?");
            arguments.add(query.status().name());
        }
        if (query.createdBefore() != null) {
            where.append(" AND r.created_at < ?");
            arguments.add(at(query.createdBefore()));
        }
        return where.toString();
    }

    private Optional<Reservation> first(String sql, Object... arguments) {
        return jdbc.query(sql, this::toReservation, arguments).stream().findFirst();
    }

    private CreateHoldResult insert(CreateHoldCommand command, TransactionStatus status) {
        Reservation reservation = command.reservation();
        insertSeatHold(reservation);
        if (!insertIdempotencyKey(command)) {
            // The key belongs to an earlier request: undo this hold and answer from the stored row.
            status.setRollbackOnly();
            return originalOf(command);
        }
        insertHoldItems(reservation);
        insertReservation(reservation);
        insertReservationSeats(reservation);
        insertOutboxEvent(command, reservation);
        return new CreateHoldResult.Created(reservation);
    }

    private void insertSeatHold(Reservation reservation) {
        jdbc.update(INSERT_SEAT_HOLD, reservation.id(), reservation.userId(), reservation.showtimeId(),
                reservation.status().name(), reservation.movieTitleSnapshot(), reservation.roomNameSnapshot(),
                at(reservation.expiresAt()), at(reservation.createdAt()));
    }

    /** @return false when the key was already used: this call created nothing worth keeping. */
    private boolean insertIdempotencyKey(CreateHoldCommand command) {
        int rows = jdbc.update(INSERT_IDEMPOTENCY_KEY, command.idempotencyKey(),
                command.reservation().id(), command.requestHash());
        return rows == 1;
    }

    private void insertHoldItems(Reservation reservation) {
        try {
            for (String seat : reservation.seatLabels()) {
                jdbc.update(INSERT_HOLD_ITEM, reservation.id(), reservation.showtimeId(), seat);
            }
        } catch (DuplicateKeyException seatIsTaken) {
            // The only constraint these rows can break is uk_seat_hold_item_active_seat: the
            // no-double-booking rule of the domain, enforced by the database itself. The message
            // is the example booking-service.yaml gives for the 422 of this route.
            throw new BusinessRuleViolationException("one or more seats are not available");
        }
    }

    private void insertReservation(Reservation reservation) {
        jdbc.update(INSERT_RESERVATION, reservation.id(), reservation.id(), reservation.userId(),
                reservation.showtimeId(), reservation.status().name(), reservation.totalAmount(),
                at(reservation.createdAt()), at(reservation.confirmedAt()));
    }

    private void insertReservationSeats(Reservation reservation) {
        for (String seat : reservation.seatLabels()) {
            jdbc.update(INSERT_RESERVATION_SEAT, reservation.id(), seat);
        }
    }

    private void insertOutboxEvent(CreateHoldCommand command, Reservation reservation) {
        UUID eventId = UUID.randomUUID();
        String envelope;
        try {
            envelope = objectMapper.writeValueAsString(envelopeOf(eventId, command, reservation));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("the outbox event of the hold cannot be serialized", exception);
        }
        jdbc.update(INSERT_OUTBOX_EVENT, eventId, AGGREGATE_TYPE, reservation.id(), EVENT_TYPE, envelope,
                at(reservation.createdAt()));
    }

    /**
     * The envelope of the platform with the correlation id of the request (ADR-008, events.md of
     * this service). csp-worker publishes it as it is, with the id of the row as messageId.
     */
    private Map<String, Object> envelopeOf(UUID eventId, CreateHoldCommand command, Reservation reservation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", reservation.id().toString());
        payload.put("userId", reservation.userId().toString());
        payload.put("showtimeId", reservation.showtimeId().toString());
        payload.put("seatNumbers", reservation.seatLabels());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("correlationId", command.correlationId());
        metadata.put("causationId", null);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", EVENT_TYPE);
        envelope.put("occurredAt", reservation.createdAt().toString());
        envelope.put("version", 1);
        envelope.put("source", EVENT_SOURCE);
        envelope.put("aggregateId", reservation.id().toString());
        envelope.put("aggregateType", AGGREGATE_TYPE);
        envelope.put("payload", payload);
        envelope.put("metadata", metadata);
        return envelope;
    }

    /** A replay answers with the original reservation, and a key with another payload is a 409. */
    private CreateHoldResult originalOf(CreateHoldCommand command) {
        StoredKey stored = jdbc.query(FIND_STORED_KEY, rs -> rs.next()
                ? new StoredKey(rs.getObject("hold_id", UUID.class), rs.getString("request_hash"))
                : null, command.idempotencyKey());
        if (stored == null) {
            throw new IllegalStateException("the stored idempotency key disappeared: " + command.idempotencyKey());
        }
        if (!stored.requestHash().equals(command.requestHash())) {
            throw new IdempotencyKeyConflictException();
        }
        Reservation original = findByHoldId(stored.holdId())
                .orElseThrow(() -> new IllegalStateException("the hold of the idempotency key has no reservation"));
        return new CreateHoldResult.Replayed(original);
    }

    private Reservation toReservation(ResultSet rs, int rowNum) throws SQLException {
        Array seats = rs.getArray("seat_labels");
        List<String> labels = seats == null
                ? List.of()
                : Arrays.stream((String[]) seats.getArray()).toList();
        return new Reservation(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("showtime_id", UUID.class),
                labels,
                ReservationStatus.valueOf(rs.getString("status")),
                instant(rs, "expires_at"),
                rs.getString("movie_title_snapshot"),
                rs.getString("room_name_snapshot"),
                rs.getLong("total_amount"),
                instant(rs, "created_at"),
                instant(rs, "confirmed_at"));
    }

    /**
     * The driver cannot infer a type for {@link Instant}, and an offset keeps the instant absolute
     * whatever the time zone of the machine or of the session is.
     */
    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * Writes the {@code ReservationExpired} outbox event in the same transaction as the release
     * (Norma 5.3.11). The envelope follows the same shape as {@code ReservationHeld}, but both
     * {@code occurredAt} and the row timestamp are the instant of the sweep, not the creation of
     * the hold: that is when the fact the event reports actually happened.
     */
    private void insertExpiredOutboxEvent(Reservation expired, Instant now, String correlationId) {
        UUID eventId = UUID.randomUUID();
        String envelope;
        try {
            envelope = objectMapper.writeValueAsString(expiredEnvelopeOf(eventId, expired, now, correlationId));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("the outbox event of the expiration cannot be serialized", exception);
        }
        jdbc.update(INSERT_OUTBOX_EVENT, eventId, AGGREGATE_TYPE, expired.id(), EXPIRED_EVENT_TYPE, envelope,
                at(now));
    }

    private Map<String, Object> expiredEnvelopeOf(UUID eventId, Reservation expired, Instant now,
            String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", expired.id().toString());
        payload.put("userId", expired.userId().toString());
        payload.put("showtimeId", expired.showtimeId().toString());
        payload.put("seatNumbers", expired.seatLabels());
        payload.put("expiredAt", expired.expiresAt().toString());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("correlationId", correlationId);
        metadata.put("causationId", null);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", EXPIRED_EVENT_TYPE);
        envelope.put("occurredAt", now.toString());
        envelope.put("version", 1);
        envelope.put("source", EVENT_SOURCE);
        envelope.put("aggregateId", expired.id().toString());
        envelope.put("aggregateType", AGGREGATE_TYPE);
        envelope.put("payload", payload);
        envelope.put("metadata", metadata);
        return envelope;
    }

    private record StoredKey(UUID holdId, String requestHash) {
    }
}
