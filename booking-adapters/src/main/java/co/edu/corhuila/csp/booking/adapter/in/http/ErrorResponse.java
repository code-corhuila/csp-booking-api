package co.edu.corhuila.csp.booking.adapter.in.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * The error envelope of the contract ({@code _shared.yaml}): {@code error}, {@code message},
 * {@code traceId} and the optional {@code details} that name every invalid field.
 * The messages never carry tokens, passwords, driver text or internal exception detail.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error, String message, List<FieldViolation> details, String traceId) {

    public record FieldViolation(String field, String message) {

        public static FieldViolation of(String field, String message) {
            return new FieldViolation(field, message);
        }
    }

    public static ErrorResponse of(String error, String message, String traceId) {
        return new ErrorResponse(error, message, null, traceId);
    }

    /**
     * The envelope of the contract with the traceId of the correlation filter, so a log line and the
     * answer of its request always carry the same id, even when nothing reached a controller yet.
     */
    public static ErrorResponse of(String error, String message) {
        String traceId = MDC.get(CorrelationIdFilter.TRACE_ID_MDC_KEY);
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        return new ErrorResponse(error, message, null, traceId);
    }
}
