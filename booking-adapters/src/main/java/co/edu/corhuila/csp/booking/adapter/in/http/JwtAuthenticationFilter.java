package co.edu.corhuila.csp.booking.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the bearer token of every protected request by itself, as the norm demands
 * (5.3.7): RS256 verified with the public key of the identity service, any other algorithm
 * rejected, {@code exp} and {@code sub} mandatory, and the user of the request is the {@code sub}.
 * A rejected token answers the envelope of the contract and never reaches a controller.
 * <p>The health routes are the only public ones, as {@code booking-service.yaml} declares.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHORIZATION_HEADER = "Authorization";
    /** Request attribute with the authenticated user: the {@code sub} of the token. */
    public static final String USER_ID_ATTRIBUTE = "userId";

    private static final String BEARER = "Bearer ";
    private static final Set<String> PUBLIC_PATHS = Set.of("/health", "/health/ready");

    private final RSAPublicKey publicKey;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(RSAPublicKey jwtPublicKey, ObjectMapper objectMapper) {
        this.publicKey = jwtPublicKey;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (PUBLIC_PATHS.contains(pathAfterContextPath(request))) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            writeError(response, "UNAUTHORIZED", "Authentication token required");
            return;
        }
        Optional<String> userId = userIdOf(header.substring(BEARER.length()).trim());
        if (userId.isEmpty()) {
            writeError(response, "INVALID_TOKEN", "the token is invalid or expired");
            return;
        }
        request.setAttribute(USER_ID_ATTRIBUTE, userId.get());
        chain.doFilter(request, response);
    }

    /** The {@code sub} of an acceptable token; empty when any rule of 5.3.7 is broken. */
    Optional<String> userIdOf(String token) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException exception) {
            logger.debug("rejected token: it is not a signed JWT");
            return Optional.empty();
        }
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            logger.debug("rejected token: the algorithm is not RS256");
            return Optional.empty();
        }
        try {
            if (!jwt.verify(new RSASSAVerifier(publicKey))) {
                logger.debug("rejected token: the signature does not match the key of this service");
                return Optional.empty();
            }
        } catch (JOSEException exception) {
            logger.debug("rejected token: the signature cannot be verified");
            return Optional.empty();
        }
        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException exception) {
            logger.debug("rejected token: its claims cannot be read");
            return Optional.empty();
        }
        Date expiresAt = claims.getExpirationTime();
        if (expiresAt == null) {
            logger.debug("rejected token: it has no exp claim");
            return Optional.empty();
        }
        if (!expiresAt.after(new Date())) {
            logger.debug("rejected token: it has expired");
            return Optional.empty();
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            logger.debug("rejected token: it has no sub claim");
            return Optional.empty();
        }
        return Optional.of(subject);
    }

    /** The route without the context path, so the check works behind and without the gateway. */
    private String pathAfterContextPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri == null || context == null || context.isEmpty()) {
            return uri;
        }
        return uri.substring(context.length());
    }

    private void writeError(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code, message));
    }
}
