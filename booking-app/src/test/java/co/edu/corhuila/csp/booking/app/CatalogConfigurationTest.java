package co.edu.corhuila.csp.booking.app;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.booking.adapter.out.catalog.HttpShowtimeCatalog;
import co.edu.corhuila.csp.booking.application.port.out.CatalogUnavailableException;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The startup rules of the Catalog client (ADR-067 section 3): fail closed, and unset keeps the 503 behavior. */
class CatalogConfigurationTest {

    private static final String HTTPS_URL = "https://catalog-api:8082/api/v1/catalog";

    private final CatalogConfiguration configuration = new CatalogConfiguration();

    @TempDir
    Path directory;

    private Path tokenFile;
    private Path caFile;

    @BeforeEach
    void files() throws Exception {
        tokenFile = Files.writeString(directory.resolve("token"), "service-token");
        caFile = directory.resolve("ca.crt");
        Path store = directory.resolve("ca.p12");
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=test-ca", "-ext", "bc:c",
                "-validity", "2", "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", "changeit");
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", store.toString(), "-storepass", "changeit",
                "-file", caFile.toString());
    }

    private static void keytool(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }

    private ShowtimeCatalog configure(String baseUrl, String tokens, String ca) {
        return configuration.showtimeCatalog(baseUrl, tokens, ca, Clock.systemUTC(), new ObjectMapper());
    }

    @Test
    void anHttpsUrlWithTheTokenFileAndTheCaFileStarts() {
        assertInstanceOf(HttpShowtimeCatalog.class, configure(HTTPS_URL, tokenFile.toString(), caFile.toString()));
    }

    @Test
    void anHttpUrlIsRefusedEvenWithTheCaFile() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> configure("http://catalog-api:8082/api/v1/catalog", tokenFile.toString(), caFile.toString()));

        assertTrue(failure.getMessage().contains("CATALOG_BASE_URL"), failure.getMessage());
        assertTrue(failure.getMessage().contains("https"), failure.getMessage());
    }

    @Test
    void aUrlWithAnotherSchemeOrNoSchemeIsRefused() {
        assertThrows(IllegalStateException.class, () -> configure("catalog-api:8082", tokenFile.toString(), caFile.toString()));
        assertThrows(IllegalStateException.class, () -> configure("ftp://catalog-api", tokenFile.toString(), caFile.toString()));
    }

    @Test
    void aMissingCaFileSettingIsRefused() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> configure(HTTPS_URL, tokenFile.toString(), ""));

        assertTrue(failure.getMessage().contains("CATALOG_TLS_CA_FILE"), failure.getMessage());
    }

    @Test
    void aCaFileThatDoesNotExistIsRefused() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> configure(HTTPS_URL, tokenFile.toString(), directory.resolve("absent.crt").toString()));

        assertTrue(failure.getMessage().contains("CATALOG_TLS_CA_FILE"), failure.getMessage());
    }

    @Test
    void theTokenFileIsStillRequiredWithTheUrl() {
        assertThrows(IllegalStateException.class, () -> configure(HTTPS_URL, "", caFile.toString()));
    }

    @Test
    void withoutTheUrlTheServiceStartsAndCatalogAnswersUnavailable() {
        ShowtimeCatalog catalog = assertDoesNotThrow(() -> configure("", "", ""));

        assertThrows(CatalogUnavailableException.class, () -> catalog.layoutOf(UUID.randomUUID(), null));
    }
}
