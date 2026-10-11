package co.edu.corhuila.csp.booking.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.csp.booking.application.port.out.CreateHoldCommand;
import co.edu.corhuila.csp.booking.domain.model.Reservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The failure that {@code ApiExceptionHandler} answers with a 503: a write whose transaction cannot
 * be opened because the database cannot be reached. It runs without PostgreSQL on purpose, against
 * a port nothing listens on, so the type that the real repository throws is checked in every build
 * and not only in the tests that need the engine.
 */
class JdbcHoldRepositoryUnreachableDatabaseTest {

    @Test
    void creatingAHoldWhenTheDatabaseCannotBeReachedFailsWithACannotCreateTransactionException() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/csp");
        config.setUsername("booking_app");
        config.setPassword("unused");
        config.setConnectionTimeout(250);
        config.setInitializationFailTimeout(-1);
        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            JdbcHoldRepository repository = new JdbcHoldRepository(new JdbcTemplate(dataSource),
                    new DataSourceTransactionManager(dataSource), new ObjectMapper());
            Reservation reservation = Reservation.hold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    List.of("A1"), Duration.ofSeconds(600), Instant.now(), "Movie", "Room 1", 0);

            assertThrows(CannotCreateTransactionException.class, () -> repository.create(
                    new CreateHoldCommand(reservation, "11111111-2222-3333-4444-555555555555", "hash",
                            "550e8400-e29b-41d4-a716-446655440000")));
        }
    }
}
