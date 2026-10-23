package co.edu.corhuila.csp.booking.adapter.out.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * The service credential Booking presents to Catalog. The deployment secret manager rotates the file
 * ({@code CATALOG_SERVICE_TOKEN_FILE}), so the token is re-read at most every 30 seconds and a rotation needs no restart
 * (ADR-020). The token is never logged.
 */
public class ServiceTokenFile implements Supplier<String> {

    private static final Duration REFRESH = Duration.ofSeconds(30);

    private final Path file;
    private final Clock clock;
    private String token;
    private Instant readAt;

    public ServiceTokenFile(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    @Override
    public synchronized String get() {
        Instant now = clock.instant();
        if (token == null || now.isAfter(readAt.plus(REFRESH))) {
            token = read();
            readAt = now;
        }
        return token;
    }

    private String read() {
        try {
            String value = Files.readString(file).strip();
            if (value.isEmpty()) {
                throw new IllegalStateException("the service token file is empty");
            }
            return value;
        } catch (IOException unreadable) {
            throw new IllegalStateException("the service token file cannot be read", unreadable);
        }
    }
}
