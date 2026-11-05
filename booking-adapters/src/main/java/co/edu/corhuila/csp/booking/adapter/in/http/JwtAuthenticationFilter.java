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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the bearer token of every protected request by itself, as the norm demands
 * (5.3.7): RS256 verified with the public key of the identity service, any other algorithm
 * rejected, {@code exp} (with {@value #CLOCK_SKEW_SECONDS} s of tolerance) and {@code sub} mandatory, and the
 * user of the request is the {@code sub}. A client route also needs the permission of the
 * {@code permissions} claim that {@link ClientRoutePermissions} lists for it, or it is a 403.
 * The access token of a user must also carry {@code iat} and live no longer than {@value #MAX_ACCESS_TOKEN_LIFETIME_SECONDS} s
 * plus the tolerance (ADR-054): only Auth reads the list of revoked tokens, so this cap keeps the window of a stolen or revoked
 * token at 15 minutes even if {@code APP_AUTH_JWT_EXPIRY} is raised.
 * A service credential (the token of a subject of {@code SERVICE_SUBJECTS} or {@code WORKFLOW_SUBJECTS})
 * must also carry {@code iss} {@value #SERVICE_ISSUER} and an {@code aud} that contains
 * {@value #SERVICE_AUDIENCE}, or it is a 401: a user's access token ({@code aud} {@code csp-api}) is never a
 * service credential, even with an allowed {@code sub} (ADR-056).
 * A rejected token answers the envelope of the contract and never reaches a controller.
 * <p>The health routes are the only public ones, as {@code booking-service.yaml} declares.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHORIZATION_HEADER = "Authorization";
    /** Request attribute with the authenticated user: the {@code sub} of the token. */
    public static final String USER_ID_ATTRIBUTE = "userId";

    private static final String BEARER = "Bearer ";
    private static final Set<String> PUBLIC_PATHS = Set.of("/health", "/health/ready");

    private static final String INTERNAL_PREFIX = "/internal/";
    private static final String WORKFLOW_PREFIX = "/internal/workflow/";
    /** Tolerance on {@code exp} for the clock difference between services (Norma 5.3.7). */
    static final long CLOCK_SKEW_SECONDS = 30;
    /** The life of the access token of a user (ADR-054); a service credential lives longer and is not capped. */
    static final long MAX_ACCESS_TOKEN_LIFETIME_SECONDS = 900;
    private static final String PERMISSIONS_CLAIM = "permissions";
    /** Issuer and audience of every service credential (ADR-056). */
    static final String SERVICE_ISSUER = "csp-auth";
    static final String SERVICE_AUDIENCE = "csp-internal";

    private final RSAPublicKey publicKey;
    private final ObjectMapper objectMapper;
    private final Set<String> serviceSubjects;
    private final Set<String> workflowSubjects;

    /**
     * @param serviceSubjects the {@code sub} of the service tokens that may call the maintenance
     *     operations under {@code /internal/} (ADR-020, {@code SERVICE_SUBJECTS}); a client token never may
     * @param workflowSubjects the {@code sub} of the service tokens that may call the operations
     *     under {@code /internal/workflow/} (ADR-035, {@code WORKFLOW_SUBJECTS}); empty keeps that
     *     family closed, and a subject of one list never reaches the operations of the other
     */
    public JwtAuthenticationFilter(RSAPublicKey jwtPublicKey, ObjectMapper objectMapper,
            @Value("${SERVICE_SUBJECTS:csp-worker}") Set<String> serviceSubjects,
            @Value("${WORKFLOW_SUBJECTS:}") Set<String> workflowSubjects) {
        this.publicKey = jwtPublicKey;
        this.objectMapper = objectMapper;
        this.serviceSubjects = Set.copyOf(serviceSubjects);
        this.workflowSubjects = Set.copyOf(workflowSubjects);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = pathAfterContextPath(request);
        if (PUBLIC_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Authentication token required");
            return;
        }
        Optional<VerifiedToken> token = verify(header.substring(BEARER.length()).trim());
        if (token.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_TOKEN", "the token is invalid or expired");
            return;
        }
        // An internal operation takes only the service token of its own family, and a service token
        // takes only the operations of its family: a valid token of the wrong kind is a 403, not a
        // 401 (ADR-020, ADR-035).
        Set<String> allowed = path != null && path.startsWith(WORKFLOW_PREFIX) ? workflowSubjects
                : path != null && path.startsWith(INTERNAL_PREFIX) ? serviceSubjects : Set.of();
        boolean internal = path != null && path.startsWith(INTERNAL_PREFIX);
        VerifiedToken verified = token.get();
        String subject = verified.subject();
        if (internal ? !allowed.contains(subject) : verified.serviceCredential() || isServiceSubject(subject)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "the token is not allowed to call this operation");
            return;
        }
        Optional<String> required = ClientRoutePermissions.requiredBy(request.getMethod(), path);
        if (required.isPresent() && !token.get().permissions().contains(required.get())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "the token does not carry the permission this operation needs");
            return;
        }
        request.setAttribute(USER_ID_ATTRIBUTE, subject);
        chain.doFilter(request, response);
    }

    /** The {@code sub} and the permissions of an acceptable token; empty when any rule of 5.3.7 is broken. */
    private Optional<VerifiedToken> verify(String token) {
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
        if (!expiresAt.after(new Date(System.currentTimeMillis() - CLOCK_SKEW_SECONDS * 1000))) {
            logger.debug("rejected token: it has expired");
            return Optional.empty();
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            logger.debug("rejected token: it has no sub claim");
            return Optional.empty();
        }
        boolean serviceCredential = carriesServiceClaims(claims);
        if (isServiceSubject(subject) && !serviceCredential) {
            logger.debug("rejected token: the sub is a service subject but iss and aud are not those of a service credential");
            return Optional.empty();
        }
        if (!serviceCredential && !isServiceSubject(subject) && !isId(subject)) {
            logger.debug("rejected token: its sub is not the id of a user of this platform");
            return Optional.empty();
        }
        if (!serviceCredential && !isServiceSubject(subject) && !hasAcceptableLifetime(claims, expiresAt)) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedToken(subject, permissionsOf(claims), serviceCredential));
    }

    /** The access token of a user carries {@code iat} and is not valid for longer than ADR-054 allows, plus the tolerance. */
    private boolean hasAcceptableLifetime(JWTClaimsSet claims, Date expiresAt) {
        Date issuedAt = claims.getIssueTime();
        if (issuedAt == null) {
            logger.debug("rejected token: it has no iat claim");
            return false;
        }
        long lifetimeSeconds = (expiresAt.getTime() - issuedAt.getTime()) / 1000;
        if (lifetimeSeconds > MAX_ACCESS_TOKEN_LIFETIME_SECONDS + CLOCK_SKEW_SECONDS) {
            logger.debug("rejected token: it lives longer than the access token of ADR-054");
            return false;
        }
        return true;
    }

    /** {@code iss} equal to {@value #SERVICE_ISSUER} and an {@code aud} that contains {@value #SERVICE_AUDIENCE} (ADR-056). */
    private static boolean carriesServiceClaims(JWTClaimsSet claims) {
        List<String> audience = claims.getAudience();
        return SERVICE_ISSUER.equals(claims.getIssuer()) && audience != null && audience.contains(SERVICE_AUDIENCE);
    }

    /** The permissions the token carries; a claim that is not a list of strings grants none. */
    private static Set<String> permissionsOf(JWTClaimsSet claims) {
        try {
            List<String> permissions = claims.getStringListClaim(PERMISSIONS_CLAIM);
            return permissions == null ? Set.of() : Set.copyOf(permissions);
        } catch (ParseException | NullPointerException exception) {
            return Set.of();
        }
    }

    private boolean isServiceSubject(String subject) {
        return serviceSubjects.contains(subject) || workflowSubjects.contains(subject);
    }

    /**
     * The {@code sub} is the id of the user in every table of this platform (the gateway also
     * forwards it as {@code X-User-Id}), so a token whose subject is not one is not acceptable
     * here: the controllers can then read the attribute as an id without a second check.
     */
    private static boolean isId(String subject) {
        try {
            UUID.fromString(subject);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
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

    private record VerifiedToken(String subject, Set<String> permissions, boolean serviceCredential) {
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code, message));
    }
}
