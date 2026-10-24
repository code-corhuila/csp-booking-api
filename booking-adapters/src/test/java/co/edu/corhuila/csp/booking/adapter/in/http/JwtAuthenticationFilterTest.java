package co.edu.corhuila.csp.booking.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every authentication rule of Norma 5.3.7, checked at the entrance of a real filter chain:
 * the correlation filter first, the token filter after it, and a controller behind.
 */
class JwtAuthenticationFilterTest {

    private static final String SUB = "11111111-1111-4111-8111-111111111111";
    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";
    private static final String SECRET = "01234567890123456789012345678901";

    private MockMvc mockMvc;
    private KeyPair serviceKey;
    private RSAPrivateKey servicePrivateKey;
    private RSAPrivateKey otherPrivateKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        serviceKey = generator.generateKeyPair();
        KeyPair otherKey = generator.generateKeyPair();
        this.mockMvc = mockMvcWith(Set.of("csp-workflow"));
        this.servicePrivateKey = (RSAPrivateKey) serviceKey.getPrivate();
        this.otherPrivateKey = (RSAPrivateKey) otherKey.getPrivate();
    }

    @Test
    void theHealthRoutesNeedNoToken() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(get("/health/ready")).andExpect(status().isOk());
    }

    @Test
    void aRequestWithoutTokenIsRejectedWithTheEnvelopeOfTheContract() throws Exception {
        mockMvc.perform(get("/holds").header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication token required"))
                .andExpect(jsonPath("$.traceId").value(CORRELATION));
    }

    @Test
    void aHeaderThatIsNotABearerTokenIsRejected() throws Exception {
        mockMvc.perform(get("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void aTokenWithAnotherAlgorithmIsRejected() throws Exception {
        JWTClaimsSet claims = validClaims();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes()));

        mockMvc.perform(get("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + jwt.serialize()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() throws Exception {
        String token = rs256Token(otherPrivateKey, validClaims());

        mockMvc.perform(get("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(SUB)
                .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                .build();
        String token = rs256Token(servicePrivateKey, claims);

        mockMvc.perform(get("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void aTokenWithoutExpirationIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(SUB).build();

        String token = rs256Token(servicePrivateKey, claims);

        mockMvc.perform(get("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void aTokenWithoutSubjectIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();

        String token = rs256Token(servicePrivateKey, claims);

        mockMvc.perform(get("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void aTokenWhoseSubjectIsNotAnIdOfThisPlatformIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("client-7")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();

        String token = rs256Token(servicePrivateKey, claims);

        mockMvc.perform(get("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void aValidTokenIdentifiesTheCallerAsItsSubject() throws Exception {
        String token = rs256Token(servicePrivateKey, validClaims());

        MvcResult result = mockMvc.perform(get("/holds")
                        .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION)
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(SUB, result.getRequest().getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE));
    }

    @Test
    void aServiceTokenCallsAnInternalOperation() throws Exception {
        String token = rs256Token(servicePrivateKey, claimsOf("csp-worker"));

        mockMvc.perform(get("/internal/probe")
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aClientTokenCallingAnInternalOperationReceivesForbidden() throws Exception {
        String token = rs256Token(servicePrivateKey, validClaims());

        mockMvc.perform(get("/internal/probe")
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void aServiceTokenCallingAClientOperationReceivesForbidden() throws Exception {
        String token = rs256Token(servicePrivateKey, claimsOf("csp-worker"));

        mockMvc.perform(get("/holds")
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void aSubjectOutsideTheServiceListCannotCallAnInternalOperation() throws Exception {
        String token = rs256Token(servicePrivateKey, claimsOf("booking-service"));

        mockMvc.perform(get("/internal/probe")
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    private MockMvc mockMvcWith(Set<String> workflowSubjects) {
        JwtAuthenticationFilter filter =
                new JwtAuthenticationFilter((RSAPublicKey) serviceKey.getPublic(), new ObjectMapper(),
                        Set.of("csp-worker"), workflowSubjects);
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new CorrelationIdFilter(), filter)
                .build();
    }

    @Test
    void aWorkflowTokenCallsAWorkflowOperation() throws Exception {
        String token = rs256Token(servicePrivateKey, claimsOf("csp-workflow"));

        mockMvc.perform(get("/internal/workflow/probe")
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aWorkflowTokenCallingAMaintenanceOperationReceivesForbidden() throws Exception {
        String token = rs256Token(servicePrivateKey, claimsOf("csp-workflow"));

        mockMvc.perform(get("/internal/probe")
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void aMaintenanceTokenCallingAWorkflowOperationReceivesForbidden() throws Exception {
        String token = rs256Token(servicePrivateKey, claimsOf("csp-worker"));

        mockMvc.perform(get("/internal/workflow/probe")
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void aClientTokenCallingAWorkflowOperationReceivesForbidden() throws Exception {
        String token = rs256Token(servicePrivateKey, validClaims());

        mockMvc.perform(get("/internal/workflow/probe")
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void aWorkflowTokenCallingAClientOperationReceivesForbidden() throws Exception {
        String token = rs256Token(servicePrivateKey, claimsOf("csp-workflow"));

        mockMvc.perform(get("/holds")
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void withNoWorkflowSubjectsTheWorkflowOperationsAreClosed() throws Exception {
        MockMvc closed = mockMvcWith(Set.of());

        closed.perform(get("/internal/workflow/probe")
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                                "Bearer " + rs256Token(servicePrivateKey, claimsOf("csp-workflow"))))
                .andExpect(status().isUnauthorized());
        closed.perform(get("/internal/workflow/probe")
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                                "Bearer " + rs256Token(servicePrivateKey, claimsOf("csp-worker"))))
                .andExpect(status().isForbidden());
        closed.perform(get("/internal/workflow/probe")
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                                "Bearer " + rs256Token(servicePrivateKey, validClaims())))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTokenThatExpiredWithinTheClockSkewIsAccepted() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(SUB)
                .expirationTime(new Date(System.currentTimeMillis() - 10_000))
                .build();

        mockMvc.perform(get("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                        "Bearer " + rs256Token(servicePrivateKey, claims)))
                .andExpect(status().isOk());
    }

    @Test
    void aTokenThatExpiredBeyondTheClockSkewIsRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(SUB)
                .expirationTime(new Date(System.currentTimeMillis() - 35_000))
                .build();

        mockMvc.perform(get("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                        "Bearer " + rs256Token(servicePrivateKey, claims)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void creatingAHoldNeedsThePermissionToCreate() throws Exception {
        mockMvc.perform(post("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                        "Bearer " + tokenWith("booking:create")))
                .andExpect(status().isOk());
    }

    @Test
    void creatingAHoldWithoutThePermissionReceivesForbidden() throws Exception {
        mockMvc.perform(post("/holds")
                        .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION)
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                                "Bearer " + tokenWith("booking:read:self", "catalog:read")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").value(CORRELATION));
    }

    @Test
    void aTokenWithoutPermissionsClaimCannotCreateAHold() throws Exception {
        mockMvc.perform(post("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                        "Bearer " + rs256Token(servicePrivateKey, validClaims())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void aPermissionsClaimThatIsNotAListGrantsNothing() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(SUB)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("permissions", "booking:create")
                .build();

        mockMvc.perform(post("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                        "Bearer " + rs256Token(servicePrivateKey, claims)))
                .andExpect(status().isForbidden());
    }

    @Test
    void confirmingAReservationNeedsThePermissionToCreate() throws Exception {
        mockMvc.perform(post("/reservations/" + SUB + "/confirm").header(
                        JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + tokenWith("booking:read:self")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reservations/" + SUB + "/confirm").header(
                        JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + tokenWith("booking:create")))
                .andExpect(status().isOk());
    }

    @Test
    void readingReservationsNeedsThePermissionToReadOwn() throws Exception {
        String reader = tokenWith("booking:read:self");
        String creatorOnly = tokenWith("booking:create");

        mockMvc.perform(get("/reservations").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + reader))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reservations/" + SUB).header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                        "Bearer " + reader))
                .andExpect(status().isOk());
        mockMvc.perform(get("/reservations").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                        "Bearer " + creatorOnly))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        mockMvc.perform(get("/reservations/" + SUB).header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                        "Bearer " + creatorOnly))
                .andExpect(status().isForbidden());
    }

    @Test
    void theUnauthorizedAnswerComesBeforeThePermissionCheck() throws Exception {
        mockMvc.perform(post("/holds"))
                .andExpect(status().isUnauthorized());
    }

    private String tokenWith(String... permissions) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(SUB)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("permissions", List.of(permissions))
                .build();
        return rs256Token(servicePrivateKey, claims);
    }

    private static JWTClaimsSet claimsOf(String subject) {
        return new JWTClaimsSet.Builder()
                .subject(subject)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
    }

    private static JWTClaimsSet validClaims() {
        return new JWTClaimsSet.Builder()
                .subject(SUB)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
    }

    private static String rs256Token(RSAPrivateKey key, JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @RestController
    static class ProbeController {

        @GetMapping("/holds")
        String hold() {
            return "held";
        }

        @PostMapping("/holds")
        String createHold() {
            return "created";
        }

        @PostMapping("/reservations/{id}/confirm")
        String confirm() {
            return "confirmed";
        }

        @GetMapping("/reservations")
        String reservations() {
            return "list";
        }

        @GetMapping("/reservations/{id}")
        String reservation() {
            return "one";
        }

        @GetMapping("/internal/probe")
        String internal() {
            return "internal";
        }

        @GetMapping("/internal/workflow/probe")
        String workflow() {
            return "workflow";
        }

        @GetMapping("/health")
        String health() {
            return "ok";
        }

        @GetMapping("/health/ready")
        String ready() {
            return "ready";
        }
    }
}
