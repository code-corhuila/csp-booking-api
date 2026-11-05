package co.edu.corhuila.csp.booking.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every call to an {@code /internal/} route leaves one structured line (csp-docs#285, STRIDE E, repudiation
 * of a leaked service credential): who the token says it is, the route, the answer, the trace id and a SHA-256
 * digest of the token, which identifies the credential without allowing anyone to use it (one-way function).
 */
class InternalCallAuditFilterTest {

    private static final String TRACE = "550e8400-e29b-41d4-a716-446655440000";

    private MockMvc mockMvc;
    private RSAPrivateKey genuineKey;
    private RSAPrivateKey strangerKey;
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private Logger auditLogger;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair genuine = generator.generateKeyPair();
        genuineKey = (RSAPrivateKey) genuine.getPrivate();
        strangerKey = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
        JwtAuthenticationFilter jwt = new JwtAuthenticationFilter((RSAPublicKey) genuine.getPublic(),
                new ObjectMapper(), Set.of("csp-worker"), Set.of("csp-workflow"));
        mockMvc = MockMvcBuilders.standaloneSetup(new Probe())
                .addFilters(new CorrelationIdFilter(), new InternalCallAuditFilter(), jwt)
                .build();
        auditLogger = (Logger) LoggerFactory.getLogger(InternalCallAuditFilter.class);
        lines.start();
        auditLogger.addAppender(lines);
    }

    @AfterEach
    void detach() {
        auditLogger.detachAppender(lines);
        lines.stop();
    }

    @Test
    void aGenuineCallLeavesOneLineWithTheSubTheRouteTheStatusTheTraceAndTheDigestOfTheToken() throws Exception {
        String token = token(genuineKey, "csp-workflow");

        mockMvc.perform(get("/internal/workflow/probe")
                .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token)
                .header(CorrelationIdFilter.CORRELATION_HEADER, TRACE));

        assertEquals(1, lines.list.size());
        ILoggingEvent line = lines.list.get(0);
        Map<String, Object> fields = fieldsOf(line);
        assertEquals("csp-workflow", fields.get("sub"));
        assertEquals("/internal/workflow/probe", fields.get("route"));
        assertEquals(200, fields.get("status"));
        assertEquals(true, fields.get("authenticated"));
        assertEquals(sha256(token), fields.get("tokenSha256"));
        assertEquals(TRACE, line.getMDCPropertyMap().get("traceId"));
        // The text of the line carries the same facts, for a log that does not render the key-value pairs.
        assertTrue(line.getFormattedMessage().contains("tokenSha256=" + sha256(token)), line.getFormattedMessage());
        assertTrue(line.getFormattedMessage().contains("sub=csp-workflow route=/internal/workflow/probe status=200"));
    }

    @Test
    void theLineNeverCarriesTheTokenNorItsSignature() throws Exception {
        String token = token(genuineKey, "csp-workflow");

        mockMvc.perform(get("/internal/workflow/probe")
                .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + token));

        String everything = lines.list.stream()
                .map(line -> line.getFormattedMessage() + " " + fieldsOf(line).values())
                .collect(Collectors.joining(" "));
        assertFalse(everything.contains(token));
        assertFalse(everything.contains(token.substring(token.lastIndexOf('.') + 1)));
        assertEquals(64, sha256(token).length());
    }

    @Test
    void aForgedTokenIsLoggedAsRejectedWithTheSubItClaimsAndItsOwnDigest() throws Exception {
        String forged = token(strangerKey, "csp-workflow");
        String genuine = token(genuineKey, "csp-workflow");

        mockMvc.perform(get("/internal/workflow/probe")
                .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + forged));
        mockMvc.perform(get("/internal/workflow/probe")
                .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + genuine));

        Map<String, Object> rejected = fieldsOf(lines.list.get(0));
        assertEquals("csp-workflow", rejected.get("sub"));
        assertEquals(401, rejected.get("status"));
        assertEquals(false, rejected.get("authenticated"));
        assertEquals(sha256(forged), rejected.get("tokenSha256"));
        assertNotEquals(rejected.get("tokenSha256"), fieldsOf(lines.list.get(1)).get("tokenSha256"));
    }

    @Test
    void aCallWithoutTokenIsLoggedWithoutDigest() throws Exception {
        mockMvc.perform(get("/internal/probe").header(CorrelationIdFilter.CORRELATION_HEADER, TRACE));

        Map<String, Object> fields = fieldsOf(lines.list.get(0));
        assertEquals(1, lines.list.size());
        assertEquals(401, fields.get("status"));
        assertEquals("none", fields.get("tokenSha256"));
        assertEquals("none", fields.get("sub"));
    }

    @Test
    void aTokenThatIsNotAJwtIsLoggedWithoutSubAndWithItsDigest() throws Exception {
        mockMvc.perform(get("/internal/probe")
                .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer not-a-jwt"));

        Map<String, Object> fields = fieldsOf(lines.list.get(0));
        assertEquals("none", fields.get("sub"));
        assertEquals(sha256("not-a-jwt"), fields.get("tokenSha256"));
    }

    @Test
    void aClientRouteIsNotAuditedByThisLine() throws Exception {
        mockMvc.perform(get("/holds").header(JwtAuthenticationFilter.AUTHORIZATION_HEADER,
                "Bearer " + token(genuineKey, "csp-workflow")));

        assertTrue(lines.list.isEmpty());
    }

    private static Map<String, Object> fieldsOf(ILoggingEvent line) {
        return line.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private static String sha256(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static String token(RSAPrivateKey key, String subject) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("csp-auth")
                .audience("csp-internal")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @RestController
    static class Probe {

        @GetMapping("/internal/workflow/probe")
        String workflow() {
            return "workflow";
        }

        @GetMapping("/internal/probe")
        String internal() {
            return "internal";
        }

        @GetMapping("/holds")
        String hold() {
            return "held";
        }
    }
}
