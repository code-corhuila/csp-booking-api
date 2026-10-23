package co.edu.corhuila.csp.booking.adapter.out.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The service credential comes from a file the secret manager rotates; it is re-read at most every 30 seconds (ADR-020). */
class ServiceTokenFileTest {

    @TempDir
    Path directory;

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-01T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void theTokenIsReadTrimmedAndKeptForThirtySecondsThenReadAgain() throws IOException {
        Path file = directory.resolve("token");
        Files.writeString(file, "first-token\n");
        MovableClock clock = new MovableClock();
        ServiceTokenFile token = new ServiceTokenFile(file, clock);

        assertEquals("first-token", token.get());

        Files.writeString(file, "second-token\n");
        clock.now = clock.now.plus(Duration.ofSeconds(29));
        assertEquals("first-token", token.get());

        clock.now = clock.now.plus(Duration.ofSeconds(2));
        assertEquals("second-token", token.get());
    }

    @Test
    void aMissingOrEmptyFileIsAConfigurationFault() throws IOException {
        Path empty = directory.resolve("empty");
        Files.writeString(empty, "  \n");

        assertThrows(IllegalStateException.class, () -> new ServiceTokenFile(directory.resolve("absent"), new MovableClock()).get());
        assertThrows(IllegalStateException.class, () -> new ServiceTokenFile(empty, new MovableClock()).get());
    }
}
