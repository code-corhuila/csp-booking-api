package co.edu.corhuila.csp.booking.app;

import co.edu.corhuila.csp.booking.adapter.out.catalog.HttpShowtimeCatalog;
import co.edu.corhuila.csp.booking.adapter.out.catalog.ServiceTokenFile;
import co.edu.corhuila.csp.booking.adapter.out.catalog.TrustedCa;
import co.edu.corhuila.csp.booking.application.port.out.CatalogUnavailableException;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The client of Catalog (ADR-020, ADR-046). {@code CATALOG_BASE_URL} is the base of the Catalog API inside the platform
 * network and {@code CATALOG_SERVICE_TOKEN_FILE} the file with the service credential. Without the base address the port
 * answers as an unavailable Catalog, so a route that needs it fails with 503 and never from invented data; with it, the
 * token file is required, the URL must be {@code https://} and {@code CATALOG_TLS_CA_FILE} must hold the internal CA
 * that is the only trust of the call (ADR-067). Timeouts: 2 seconds to connect and 3 to read.
 */
@Configuration
public class CatalogConfiguration {

    @Bean
    ShowtimeCatalog showtimeCatalog(
            @Value("${catalog.base-url:}") String baseUrl,
            @Value("${catalog.service-token-file:}") String tokenFile,
            @Value("${catalog.tls-ca-file:}") String caFile,
            Clock clock,
            ObjectMapper mapper) {
        if (baseUrl.isBlank()) {
            return (showtimeId, correlationId) -> {
                throw new CatalogUnavailableException("catalog.base-url (CATALOG_BASE_URL) is not configured");
            };
        }
        if (tokenFile.isBlank()) {
            throw new IllegalStateException(
                    "catalog.service-token-file (CATALOG_SERVICE_TOKEN_FILE) is required when CATALOG_BASE_URL is set");
        }
        if (!baseUrl.startsWith("https://")) {
            throw new IllegalStateException(
                    "catalog.base-url (CATALOG_BASE_URL) must be an https:// URL: the call to Catalog is always over TLS");
        }
        if (caFile.isBlank()) {
            throw new IllegalStateException(
                    "catalog.tls-ca-file (CATALOG_TLS_CA_FILE) is required when CATALOG_BASE_URL is set");
        }
        SSLContext trust = trustOf(caFile);
        return new HttpShowtimeCatalog(baseUrl, new ServiceTokenFile(Path.of(tokenFile), clock), trust,
                Duration.ofSeconds(2), Duration.ofSeconds(3), mapper);
    }

    private static SSLContext trustOf(String caFile) {
        try {
            return TrustedCa.sslContext(Path.of(caFile));
        } catch (IllegalStateException unreadable) {
            throw new IllegalStateException(
                    "catalog.tls-ca-file (CATALOG_TLS_CA_FILE) cannot be used as the CA of Catalog", unreadable);
        }
    }
}
