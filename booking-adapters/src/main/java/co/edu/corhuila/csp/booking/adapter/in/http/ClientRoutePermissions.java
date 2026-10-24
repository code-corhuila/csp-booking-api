package co.edu.corhuila.csp.booking.adapter.in.http;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The permission of the {@code permissions} claim that each client route requires (Norma 5.6.2),
 * with the names of the permissions table of {@code 07-api/authentication.md}. A route that is not
 * listed needs a valid token and nothing more.
 */
final class ClientRoutePermissions {

    static final String CREATE = "booking:create";
    static final String READ_OWN = "booking:read:self";

    private static final Pattern CONFIRM = Pattern.compile("/reservations/[^/]+/confirm");
    private static final Pattern RESERVATION = Pattern.compile("/reservations/[^/]+");

    private ClientRoutePermissions() {
    }

    /** The permission the route needs, or empty when it asks for none. */
    static Optional<String> requiredBy(String method, String path) {
        if (path == null) {
            return Optional.empty();
        }
        if ("POST".equals(method) && ("/holds".equals(path) || CONFIRM.matcher(path).matches())) {
            return Optional.of(CREATE);
        }
        if ("GET".equals(method) && ("/reservations".equals(path) || RESERVATION.matcher(path).matches())) {
            return Optional.of(READ_OWN);
        }
        return Optional.empty();
    }
}
