package co.edu.corhuila.csp.booking.adapter.in.http;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/workflow/holds} against the real filter chain (STRIDE E, csp-docs#285): a token whose
 * {@code sub} is {@code csp-workflow} but that the identity service did not sign never reaches the operation, however
 * well it imitates a service credential.
 */
class ForgedWorkflowTokenAuthzTest {

    private static final String PATH = "/internal/workflow/holds";
    private static final String SECRET = "01234567890123456789012345678901";

    private MockMvc mockMvc;
    private RSAPrivateKey genuineKey;
    private RSAPrivateKey strangerKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair genuine = generator.generateKeyPair();
        strangerKey = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
        genuineKey = (RSAPrivateKey) genuine.getPrivate();
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter((RSAPublicKey) genuine.getPublic(),
                new ObjectMapper(), Set.of("csp-worker"), Set.of("csp-workflow"));
        mockMvc = MockMvcBuilders.standaloneSetup(new HoldsProbe())
                .addFilters(new CorrelationIdFilter(), filter)
                .build();
    }

    @Test
    void aWorkflowTokenSignedWithAnotherKeyIsRejected() throws Exception {
        SignedJWT forged = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), workflowClaims());
        forged.sign(new RSASSASigner(strangerKey));

        mockMvc.perform(post(PATH).header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + forged.serialize()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void aWorkflowTokenSignedWithHs256IsRejected() throws Exception {
        SignedJWT forged = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), workflowClaims());
        forged.sign(new MACSigner(SECRET.getBytes()));

        mockMvc.perform(post(PATH).header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + forged.serialize()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"));
    }

    @Test
    void aCallWithoutTokenIsUnauthorizedAndAGenuineTokenIsTheOnlyOneThatPasses() throws Exception {
        mockMvc.perform(post(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        SignedJWT genuineToken = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), workflowClaims());
        genuineToken.sign(new RSASSASigner(genuineKey));
        mockMvc.perform(post(PATH)
                        .header(JwtAuthenticationFilter.AUTHORIZATION_HEADER, "Bearer " + genuineToken.serialize()))
                .andExpect(status().isOk());
    }

    /** The claims of the credential of the workflow as the identity service issues them (ADR-052, ADR-056). */
    private static JWTClaimsSet workflowClaims() {
        return new JWTClaimsSet.Builder()
                .subject("csp-workflow")
                .issuer("csp-auth")
                .audience("csp-internal")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
    }

    @RestController
    static class HoldsProbe {

        @PostMapping(PATH)
        String hold() {
            return "held";
        }
    }
}
