package co.edu.corhuila.csp.booking.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.booking.adapter.in.http.CorrelationIdFilter;
import co.edu.corhuila.csp.booking.adapter.in.http.JwtAuthenticationFilter;
import co.edu.corhuila.csp.booking.application.port.in.ReservationUseCases;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingApplicationTests {

    /** The key of this context is generated, never committed: it stands for JWT_PUBLIC_KEY. */
    private static final RsaTestKey JWT_KEY = RsaTestKey.generate();

    /** The base of the contract is part of the root URI of TestRestTemplate (context path), so the
     * routes here are the paths after it: the request goes to /api/v1/booking/holds. */
    private static final String HOLDS = "/holds";
    private static final String SUB = "11111111-1111-4111-8111-111111111111";
    private static final String BODY = """
            {"showtimeId":"33333333-3333-3333-3333-333333333333","seatLabels":["A1"],\
            "movieTitle":"Movie","roomName":"Room 1"}""";

    @DynamicPropertySource
    static void jwtKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", JWT_KEY::publicKeyPem);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void theCompositionRootStarts() {
        assertNotNull(context.getBean(BookingApplication.class));
    }

    @Test
    void theAdaptersArePartOfTheSameContext() {
        assertNotNull(context.getBean(CorrelationIdFilter.class));
    }

    @Test
    void theServiceValidatesTheTokenWithItsOwnPublicKey() {
        assertNotNull(context.getBean(JwtAuthenticationFilter.class));
        assertNotNull(context.getBean(RSAPublicKey.class));
    }

    @Test
    void theUseCasesAreWiredWithTheirPortsAndTheirClock() {
        assertNotNull(context.getBean(ReservationUseCases.class));
        assertNotNull(context.getBean(Clock.class));
    }

    @Test
    void theRouteOfTheContractAnswersWithItsOwnEnvelopeWhenThereIsNoToken() {
        ResponseEntity<String> answer = rest.postForEntity(HOLDS, new HttpEntity<>(BODY, headers(null)), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, answer.getStatusCode());
        assertTrue(answer.getBody().contains("\"error\":\"UNAUTHORIZED\""), answer.getBody());
    }

    @Test
    void aKeyOutsideTheBoundsOfTheContractIsA400BeforeAnythingReachesTheDatabase() {
        HttpHeaders headers = headers(JWT_KEY.token(SUB));
        headers.set("Idempotency-Key", "too-short");

        ResponseEntity<String> answer = rest.postForEntity(HOLDS, new HttpEntity<>(BODY, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, answer.getStatusCode(), answer.getBody());
        assertTrue(answer.getBody().contains("\"error\":\"VALIDATION_ERROR\""), answer.getBody());
        assertTrue(answer.getBody().contains("idempotencyKey"), answer.getBody());
    }

    @Test
    void theHealthOfTheContractAnswersBehindTheBaseOfTheService() {
        // /health on the root URI of TestRestTemplate is /api/v1/booking/health: the service owns
        // the prefix of booking-service.yaml and the filter keeps the route public behind it.
        ResponseEntity<String> answer = rest.getForEntity("/health", String.class);

        assertEquals(HttpStatus.OK, answer.getStatusCode(), answer.getBody());
        assertTrue(answer.getBody().contains("\"status\":\"ok\""), answer.getBody());
    }

    private static HttpHeaders headers(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.set("Authorization", "Bearer " + bearerToken);
        }
        return headers;
    }
}
