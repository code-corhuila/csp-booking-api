package co.edu.corhuila.csp.booking.adapter.in.http;

import co.edu.corhuila.csp.booking.application.port.out.ReservationPage;
import java.util.List;

/** The page of the list of the contract: the reservations and the meta every list carries. */
public record ReservationListResponse(List<ReservationResponse> data, Meta meta) {

    /** {@code PaginatedMeta} of {@code _shared.yaml}: where this page is and how many exist. */
    public record Meta(int page, int limit, long total, long totalPages) {
    }

    public static ReservationListResponse of(ReservationPage page, int requestedPage, int requestedLimit) {
        long totalPages = (page.total() + requestedLimit - 1) / requestedLimit;
        return new ReservationListResponse(
                page.items().stream().map(ReservationResponse::of).toList(),
                new Meta(requestedPage, requestedLimit, page.total(), totalPages));
    }
}
