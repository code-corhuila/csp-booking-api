package co.edu.corhuila.csp.booking.application.port.out;

/**
 * The same Idempotency-Key arrived with another payload. The contract answers 409 instead of
 * guessing which of the two requests the client means (Norma 5.3.8).
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        super("the Idempotency-Key was already used with another payload");
    }
}
