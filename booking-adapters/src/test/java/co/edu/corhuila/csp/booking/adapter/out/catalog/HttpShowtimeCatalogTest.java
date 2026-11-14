package co.edu.corhuila.csp.booking.adapter.out.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.booking.application.port.out.CatalogUnavailableException;
import co.edu.corhuila.csp.booking.application.port.out.LayoutSeat;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeLayout;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeNotAvailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The client of the availability operation against a fake Catalog that answers what catalog-service.yaml 1.9.0 declares,
 * over TLS with a throwaway CA (ADR-067). {@code localhost} is the name in the URL of the tests, so it stands for
 * {@code catalog-api} in the certificates.
 */
class HttpShowtimeCatalogTest {

    private static final UUID SHOWTIME = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";
    private static final String OK_BODY = """
            {"showtimeId":"33333333-3333-3333-3333-333333333333","movieTitle":"Movie","roomName":"Room 1",
             "startsAt":"2026-11-01T20:00:00Z","endsAt":"2026-11-01T22:00:00Z","price":12000,
             "seats":["A1","Z9"],
             "seatLayout":[{"label":"A1","row":"A","number":1,"type":"STANDARD"},{"label":"Z9","type":"ACCESSIBLE"}]}""";

    private static final String NAME_IN_URL = "localhost";

    @TempDir
    Path directory;

    private ThrowawayPki ca;
    private HttpsServer server;
    private int status;
    private String body;
    private final AtomicReference<com.sun.net.httpserver.Headers> seenHeaders = new AtomicReference<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();

    @BeforeEach
    void startCatalog() throws Exception {
        status = 200;
        body = OK_BODY;
        ca = ThrowawayPki.authority(directory, "csp-internal-ca");
        server = startServer(ca, NAME_IN_URL);
    }

    private HttpsServer startServer(ThrowawayPki authority, String... names) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(authority.serverContext(names)));
        server.createContext("/api/v1/catalog", exchange -> {
            seenHeaders.set(exchange.getRequestHeaders());
            seenPath.set(exchange.getRequestURI().getPath());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    @AfterEach
    void stopCatalog() {
        server.stop(0);
    }

    private HttpShowtimeCatalog client(Duration readTimeout) {
        return client(server, ca.certificateFile(), readTimeout);
    }

    private HttpShowtimeCatalog client(HttpsServer target, Path caFile, Duration readTimeout) {
        return new HttpShowtimeCatalog(
                "https://" + NAME_IN_URL + ":" + target.getAddress().getPort() + "/api/v1/catalog",
                () -> "service-token", TrustedCa.sslContext(caFile), Duration.ofSeconds(2), readTimeout,
                new ObjectMapper());
    }

    @Test
    void theLayoutIsReadWithTheServiceCredentialAndTheCorrelationId() {
        ShowtimeLayout layout = client(Duration.ofSeconds(3)).layoutOf(SHOWTIME, CORRELATION);

        assertEquals("/api/v1/catalog/showtimes/" + SHOWTIME + "/availability", seenPath.get());
        assertEquals("Bearer service-token", seenHeaders.get().getFirst("Authorization"));
        assertEquals(CORRELATION, seenHeaders.get().getFirst("X-Correlation-Id"));
        assertEquals(SHOWTIME, layout.showtimeId());
        assertEquals("Movie", layout.movieTitle());
        assertEquals("Room 1", layout.roomName());
        assertEquals(Instant.parse("2026-11-01T20:00:00Z"), layout.startsAt());
        assertEquals(Instant.parse("2026-11-01T22:00:00Z"), layout.endsAt());
        assertEquals(12000, layout.price());
        assertEquals(List.of(new LayoutSeat("A1", "A", 1, "STANDARD"), new LayoutSeat("Z9", null, null, "ACCESSIBLE")),
                layout.seats());
        assertNull(layout.seats().get(1).row());
    }

    @Test
    void aShowtimeCatalogDoesNotHaveIsNotAvailable() {
        status = 404;
        body = "{\"error\":\"NOT_FOUND\"}";

        assertThrows(ShowtimeNotAvailableException.class, () -> client(Duration.ofSeconds(3)).layoutOf(SHOWTIME, CORRELATION));
    }

    @Test
    void aServerErrorOfCatalogIsUnavailable() {
        status = 503;
        body = "{\"error\":\"SERVICE_UNAVAILABLE\"}";

        assertThrows(CatalogUnavailableException.class, () -> client(Duration.ofSeconds(3)).layoutOf(SHOWTIME, CORRELATION));
    }

    @Test
    void aCredentialThatCatalogRefusesIsAConfigurationFaultAndNotAnUnavailableCatalog() {
        status = 403;
        body = "{\"error\":\"FORBIDDEN\"}";

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client(Duration.ofSeconds(3)).layoutOf(SHOWTIME, CORRELATION));
        assertTrue(failure.getMessage().contains("403"));
    }

    @Test
    void anAnswerWithoutSeatLayoutIsUnavailableBecauseTheMapIsNeverMadeUp() {
        body = OK_BODY.replaceAll("\"seatLayout\":\\[.*\\]", "\"extra\":1");

        assertThrows(CatalogUnavailableException.class, () -> client(Duration.ofSeconds(3)).layoutOf(SHOWTIME, CORRELATION));
    }

    @Test
    void aCatalogThatIsDownIsUnavailable() {
        HttpShowtimeCatalog down = new HttpShowtimeCatalog("https://127.0.0.1:1/api/v1/catalog",
                () -> "service-token", TrustedCa.sslContext(ca.certificateFile()), Duration.ofMillis(500), Duration.ofSeconds(1), new ObjectMapper());

        assertThrows(CatalogUnavailableException.class, () -> down.layoutOf(SHOWTIME, CORRELATION));
    }

    @Test
    void aCatalogThatDoesNotAnswerInTimeIsUnavailable() {
        server.removeContext("/api/v1/catalog");
        server.createContext("/api/v1/catalog", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });

        assertThrows(CatalogUnavailableException.class, () -> client(Duration.ofMillis(300)).layoutOf(SHOWTIME, CORRELATION));
    }

    @Test
    void aServerSignedByAnotherAuthorityFailsTheHandshakeAndIsUnavailable() throws Exception {
        ThrowawayPki stranger = ThrowawayPki.authority(directory, "another-ca");
        HttpsServer untrusted = startServer(stranger, NAME_IN_URL);
        try {
            CatalogUnavailableException failure = assertThrows(CatalogUnavailableException.class,
                    () -> client(untrusted, ca.certificateFile(), Duration.ofSeconds(3)).layoutOf(SHOWTIME, CORRELATION));

            assertTrue(hasCause(failure, SSLHandshakeException.class), "the cause is the failed handshake");
            assertNull(seenPath.get(), "nothing reached the handler of the untrusted server");
        } finally {
            untrusted.stop(0);
        }
    }

    @Test
    void aCertificateWhoseNameIsNotTheOneInTheUrlIsRefusedEvenWhenTheRightAuthoritySignedIt() throws Exception {
        HttpsServer otherName = startServer(ca, "other-service");
        try {
            CatalogUnavailableException failure = assertThrows(CatalogUnavailableException.class,
                    () -> client(otherName, ca.certificateFile(), Duration.ofSeconds(3)).layoutOf(SHOWTIME, CORRELATION));

            assertTrue(hasCause(failure, SSLHandshakeException.class), "the cause is the failed name verification");
            assertNull(seenPath.get(), "nothing reached the handler of the server with the wrong name");
        } finally {
            otherName.stop(0);
        }
    }

    @Test
    void aCaFileThatIsMissingOrIsNotACertificateIsRefused() {
        Path notACertificate = directory.resolve("empty.crt");
        assertThrows(IllegalStateException.class, () -> {
            Files.writeString(notACertificate, "not a certificate");
            TrustedCa.sslContext(notACertificate);
        });
        assertThrows(IllegalStateException.class, () -> TrustedCa.sslContext(directory.resolve("missing.crt")));
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
