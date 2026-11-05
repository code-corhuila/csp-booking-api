package co.edu.corhuila.csp.booking.adapter.in.http;

import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One structured line for every call to an {@code /internal/} route, answered or rejected (STRIDE E and R,
 * csp-docs#285): the {@code sub} the token claims, the route, the status, whether the token passed every check and
 * a SHA-256 digest of the token. The trace id travels in the MDC, put there by {@link CorrelationIdFilter}, which runs
 * before this filter.
 *
 * <p>The digest is a one-way function: it lets an operator tell which credential made a call, and whether two calls
 * used the same one, without the line ever being able to replay it. The token itself is never logged. The {@code sub} of
 * a rejected token is read without verifying the signature, so on a line with {@code authenticated=false} it is only
 * what the caller claimed, which is what an investigation of a forged token needs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class InternalCallAuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalCallAuditFilter.class);
    private static final String INTERNAL_PREFIX = "/internal/";
    private static final String BEARER = "Bearer ";
    private static final String NONE = "none";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String route = routeOf(request);
        if (route == null || !route.startsWith(INTERNAL_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            audit(request, response, route);
        }
    }

    private void audit(HttpServletRequest request, HttpServletResponse response, String route) {
        String token = bearerTokenOf(request);
        boolean authenticated = request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) != null;
        String subject = token == null ? NONE : claimedSubjectOf(token);
        String digest = token == null ? NONE : sha256(token);
        int status = response.getStatus();
        (status >= 400 ? log.atWarn() : log.atInfo())
                .addKeyValue("sub", subject)
                .addKeyValue("route", route)
                .addKeyValue("status", status)
                .addKeyValue("authenticated", authenticated)
                .addKeyValue("tokenSha256", digest)
                .log("internal call sub={} route={} status={} authenticated={} tokenSha256={}", subject, route, status,
                        authenticated, digest);
    }

    /** The route without the context path, so the audit works behind and without the gateway. */
    private static String routeOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri == null || context == null || context.isEmpty()) {
            return uri;
        }
        return uri.substring(context.length());
    }

    private static String bearerTokenOf(HttpServletRequest request) {
        String header = request.getHeader(JwtAuthenticationFilter.AUTHORIZATION_HEADER);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** The {@code sub} the token says it has, without trusting it: {@code none} when it cannot be read. */
    private static String claimedSubjectOf(String token) {
        try {
            String subject = SignedJWT.parse(token).getJWTClaimsSet().getSubject();
            return subject == null || subject.isBlank() ? NONE : subject;
        } catch (ParseException exception) {
            return NONE;
        }
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
