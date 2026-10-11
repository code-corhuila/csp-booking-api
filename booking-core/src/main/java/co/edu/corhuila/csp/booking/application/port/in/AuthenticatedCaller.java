package co.edu.corhuila.csp.booking.application.port.in;

import java.util.Objects;
import java.util.UUID;

/**
 * The user on whose behalf a request is served: the {@code sub} of an already validated token. The
 * core declares it so that no transport detail (a servlet attribute, a header name) decides how the
 * identity reaches the use cases; an adapter builds it, a controller receives it.
 */
public record AuthenticatedCaller(UUID userId) {

    public AuthenticatedCaller {
        Objects.requireNonNull(userId, "the user of the token is required");
    }
}
