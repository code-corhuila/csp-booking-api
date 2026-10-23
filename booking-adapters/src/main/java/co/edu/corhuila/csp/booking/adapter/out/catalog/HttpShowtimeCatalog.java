package co.edu.corhuila.csp.booking.adapter.out.catalog;

import co.edu.corhuila.csp.booking.application.port.out.CatalogUnavailableException;
import co.edu.corhuila.csp.booking.application.port.out.LayoutSeat;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeCatalog;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeLayout;
import co.edu.corhuila.csp.booking.application.port.out.ShowtimeNotAvailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Reads {@code GET /showtimes/{showtimeId}/availability} of Catalog with the service credential, directly inside the
 * platform network and not through the gateway (ADR-020, ADR-046). The failures are the ones ADR-020 lists: a {@code 404} is
 * a showtime that is not available, a timeout, a connection error or a {@code 5xx} is an unavailable Catalog, and a
 * {@code 401} or {@code 403} is a configuration fault. There is no retry inside the request and no fallback: an answer
 * without {@code seatLayout} is not turned into a layout with invented rows.
 */
public class HttpShowtimeCatalog implements ShowtimeCatalog {

    private final String baseUrl;
    private final Supplier<String> serviceToken;
    private final Duration readTimeout;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public HttpShowtimeCatalog(String baseUrl, Supplier<String> serviceToken, Duration connectTimeout,
            Duration readTimeout, ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serviceToken = serviceToken;
        this.readTimeout = readTimeout;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public ShowtimeLayout layoutOf(UUID showtimeId, String correlationId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/showtimes/" + showtimeId + "/availability"))
                .timeout(readTimeout)
                .header("Authorization", "Bearer " + serviceToken.get())
                .header("Accept", "application/json")
                .GET();
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        HttpResponse<String> response = send(request.build());
        int status = response.statusCode();
        if (status == 200) {
            return parse(showtimeId, response.body());
        }
        if (status == 404) {
            throw new ShowtimeNotAvailableException(showtimeId);
        }
        if (status == 401 || status == 403) {
            throw new IllegalStateException("catalog refused the service credential with " + status);
        }
        throw new CatalogUnavailableException("catalog answered " + status);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException unreachable) {
            throw new CatalogUnavailableException("catalog cannot be reached", unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CatalogUnavailableException("the call to catalog was interrupted", interrupted);
        }
    }

    private ShowtimeLayout parse(UUID showtimeId, String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode layout = root.get("seatLayout");
            if (layout == null || !layout.isArray()) {
                throw new CatalogUnavailableException("catalog answered without seatLayout");
            }
            List<LayoutSeat> seats = new ArrayList<>();
            for (JsonNode seat : layout) {
                seats.add(new LayoutSeat(seat.get("label").asText(), text(seat, "row"),
                        seat.hasNonNull("number") ? seat.get("number").asInt() : null, seat.get("type").asText()));
            }
            return new ShowtimeLayout(showtimeId, root.get("movieTitle").asText(), root.get("roomName").asText(),
                    Instant.parse(root.get("startsAt").asText()), Instant.parse(root.get("endsAt").asText()),
                    root.get("price").asLong(), seats);
        } catch (IOException | RuntimeException malformed) {
            if (malformed instanceof CatalogUnavailableException unavailable) {
                throw unavailable;
            }
            throw new CatalogUnavailableException("catalog answered something that is not its contract", malformed);
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
