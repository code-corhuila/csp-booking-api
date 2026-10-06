package co.edu.corhuila.csp.booking.application.port.out;

/**
 * The same Idempotency-Key arrived with another payload. The contract answers 409 instead of
 * guessing which of the two requests the client means (Norma 5.3.8).
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        // The message of the example booking-service.yaml gives for this 409.
        super("Idempotency key has already been used with a different payload");
    }
}
