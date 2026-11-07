package co.edu.corhuila.csp.booking.application.port.out;

/** Catalog cannot be reached or answered a server error: the client retries, nothing is invented (ADR-020). */
public class CatalogUnavailableException extends RuntimeException {

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public CatalogUnavailableException(String message) {
        super(message);
    }
}
