package co.edu.corhuila.csp.booking.application.port.out;

import co.edu.corhuila.csp.booking.domain.model.ReservationStatus;
import java.time.Instant;

/**
 * Window and filters of the list of the reservations of one user. The page starts at 1 and the
 * limit is bounded as the contract declares (1 to 100), so the SQL can never be asked for a page
 * that the norm forbids (5.3.10).
 */
public record ReservationQuery(int page, int limit, ReservationStatus status, Instant createdBefore) {

    public ReservationQuery {
        if (page < 1) {
            throw new IllegalArgumentException("the page starts at 1");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("the limit is between 1 and 100");
        }
    }

    /** Rows to skip before the first one of the page. */
    public long offset() {
        return (long) (page - 1) * limit;
    }
}
