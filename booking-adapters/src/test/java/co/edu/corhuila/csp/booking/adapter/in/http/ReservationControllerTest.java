package co.edu.corhuila.csp.booking.adapter.in.http;

import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.csp.booking.application.port.in.CreateHoldInput;
import co.edu.corhuila.csp.booking.application.port.in.ReservationUseCases;
import co.edu.corhuila.csp.booking.application.port.out.CreateHoldResult;
import co.edu.corhuila.csp.booking.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.booking.application.port.out.ReservationPage;
import co.edu.corhuila.csp.booking.application.port.out.ReservationQuery;
import co.edu.corhuila.csp.booking.domain.model.BusinessRuleViolationException;
import co.edu.corhuila.csp.booking.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.booking.domain.model.Reservation;
import co.edu.corhuila.csp.booking.domain.model.ReservationAccessDeniedException;
import co.edu.corhuila.csp.booking.domain.model.ReservationNotFoundException;
import co.edu.corhuila.csp.booking.domain.model.ReservationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * {@code POST /holds} with every answer {@code booking-service.yaml} declares around it: 201, the
 * 200 of the replay, 400, 404 of an unknown route, 409, 422, 500 and 503. The caller is already
 * authenticated here; the token filter that protects the route has its own tests and the route
 * itself is checked end to end in the application tests.
 */
class ReservationControllerTest {

    private static final String SUB = "11111111-1111-4111-8111-111111111111";
    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";
    private static final String KEY = "6b1f0d2e-4a3c-4f1a-9c2d-8e7f6a5b4c3d";
    private static final UUID SHOWTIME = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String BODY = """
            {"showtimeId":"33333333-3333-3333-3333-333333333333","seatLabels":["A1","A2"],\
            "movieTitle":"Movie","roomName":"Room 1"}""";

    private ReservationUseCases useCases;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCases = mock(ReservationUseCases.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ReservationController(useCases))
                .addFilters(new CorrelationIdFilter())
                .setCustomArgumentResolvers(new AuthenticatedCallerArgumentResolver())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void aHoldTheServiceMakesAnswers201WithTheLocationOfTheReservation() throws Exception {
        Reservation created = reservation();
        when(useCases.createHold(any())).thenReturn(new CreateHoldResult.Created(created));

        mockMvc.perform(hold(KEY, BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/reservations/" + created.id())))
                .andExpect(jsonPath("$.id").value(created.id().toString()))
                .andExpect(jsonPath("$.userId").value(SUB))
                .andExpect(jsonPath("$.showtimeId").value(SHOWTIME.toString()))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.seatLabels[0]").value("A1"))
                .andExpect(jsonPath("$.seatLabels[1]").value("A2"))
                .andExpect(jsonPath("$.movieTitleSnapshot").value("Movie"))
                .andExpect(jsonPath("$.roomNameSnapshot").value("Room 1"))
                .andExpect(jsonPath("$.totalAmount").value(0))
                // The shape of the instants is the date-time of the contract; it is asserted
                // against the configuration the service boots with in the application tests.
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.confirmedAt").doesNotExist());

        ArgumentCaptor<CreateHoldInput> input = ArgumentCaptor.forClass(CreateHoldInput.class);
        verify(useCases).createHold(input.capture());
        assertEquals(UUID.fromString(SUB), input.getValue().userId());
        assertEquals(KEY, input.getValue().idempotencyKey());
        assertEquals(CORRELATION, input.getValue().correlationId());
        assertEquals(600, input.getValue().holdDurationSeconds());
    }

    @Test
    void aKeyThatAlreadyMadeThisHoldAnswers200WithTheOriginalReservation() throws Exception {
        Reservation original = reservation();
        when(useCases.createHold(any())).thenReturn(new CreateHoldResult.Replayed(original));

        mockMvc.perform(hold(KEY, BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(original.id().toString()))
                .andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    void aBodyWithoutTheShowtimeIsA400ThatNamesTheField() throws Exception {
        mockMvc.perform(hold(KEY, "{\"seatLabels\":[\"A1\"],\"movieTitle\":\"Movie\",\"roomName\":\"Room 1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid input data"))
                .andExpect(jsonPath("$.details[0].field").value("showtimeId"))
                .andExpect(jsonPath("$.traceId").value(CORRELATION));
    }

    @Test
    void aDurationOutsideTheRangeOfTheContractIsA400() throws Exception {
        String body = BODY.replace("\"roomName\":\"Room 1\"", "\"roomName\":\"Room 1\",\"holdDurationSeconds\":30");

        mockMvc.perform(hold(KEY, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("holdDurationSeconds"));
    }

    @Test
    void aSeatAnotherClientHoldsIsA422WithTheMessageOfTheContract() throws Exception {
        when(useCases.createHold(any()))
                .thenThrow(new BusinessRuleViolationException("one or more seats are not available"));

        mockMvc.perform(hold(KEY, BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value("one or more seats are not available"))
                .andExpect(jsonPath("$.traceId").value(CORRELATION));
    }

    @Test
    void aKeyUsedWithAnotherPayloadIsA409() throws Exception {
        when(useCases.createHold(any())).thenThrow(new IdempotencyKeyConflictException());

        mockMvc.perform(hold(KEY, BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Idempotency key has already been used with a different payload"));
    }

    @Test
    void anUnknownRouteAnswersTheEnvelopeOfTheContract() throws Exception {
        mockMvc.perform(get("/no-such-route").header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").value(CORRELATION));
    }

    @Test
    void anIdOfTheRouteThatIsNotAUuidIsA400() throws Exception {
        mockMvc.perform(read("/reservations/no-such-one"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void aDatabaseThatCannotBeReachedIsA503() throws Exception {
        when(useCases.createHold(any())).thenThrow(new CannotGetJdbcConnectionException("connection refused"));

        mockMvc.perform(hold(KEY, BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("A required service or dependency is unavailable"));
    }

    @Test
    void aTransactionThatCannotBeOpenedIsA503() throws Exception {
        when(useCases.createHold(any())).thenThrow(new CannotCreateTransactionException(
                "Could not open JDBC Connection for transaction", new IllegalStateException("pool exhausted")));

        mockMvc.perform(hold(KEY, BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("A required service or dependency is unavailable"));
    }

    @Test
    void anUnexpectedFailureIsA500WithTheEnvelopeAndNoStackTrace() throws Exception {
        when(useCases.createHold(any())).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(hold(KEY, BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    @Test
    void aReservationOfTheCallerIsAnsweredByItsId() throws Exception {
        Reservation reservation = reservation();
        when(useCases.getReservation(UUID.fromString(SUB), reservation.id())).thenReturn(reservation);

        mockMvc.perform(read("/reservations/" + reservation.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reservation.id().toString()))
                .andExpect(jsonPath("$.userId").value(SUB))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.seatLabels[0]").value("A1"));
    }

    @Test
    void theReservationOfAnotherUserIsA403() throws Exception {
        UUID foreign = UUID.randomUUID();
        when(useCases.getReservation(any(UUID.class), any(UUID.class)))
                .thenThrow(new ReservationAccessDeniedException(foreign, UUID.fromString(SUB)));

        mockMvc.perform(read("/reservations/" + foreign))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(
                        "reservation " + foreign + " belongs to another user (caller " + SUB + ")"));
    }

    @Test
    void anUnknownReservationIsA404() throws Exception {
        UUID missing = UUID.randomUUID();
        when(useCases.getReservation(any(UUID.class), any(UUID.class)))
                .thenThrow(new ReservationNotFoundException(missing));

        mockMvc.perform(read("/reservations/" + missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("reservation " + missing + " not found"));
    }

    @Test
    void theListIsThePageTheCallerAskedForWithTheMetaOfTheContract() throws Exception {
        when(useCases.listReservations(any(UUID.class), any(ReservationQuery.class)))
                .thenReturn(new ReservationPage(List.of(reservation(), reservation()), 42L));

        mockMvc.perform(read("/reservations").param("page", "2").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("HELD"))
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.limit").value(3))
                .andExpect(jsonPath("$.meta.total").value(42))
                .andExpect(jsonPath("$.meta.totalPages").value(14));
    }

    @Test
    void theFiltersOfTheListReachTheUseCaseAndAnEmptyPageIsANormalAnswer() throws Exception {
        when(useCases.listReservations(any(UUID.class), any(ReservationQuery.class)))
                .thenReturn(new ReservationPage(List.of(), 0L));

        mockMvc.perform(read("/reservations").param("status", "HELD").param("createdBefore", "2026-10-05T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.total").value(0))
                .andExpect(jsonPath("$.meta.totalPages").value(0));

        ArgumentCaptor<ReservationQuery> query = ArgumentCaptor.forClass(ReservationQuery.class);
        verify(useCases).listReservations(eq(UUID.fromString(SUB)), query.capture());
        assertEquals(1, query.getValue().page());
        assertEquals(20, query.getValue().limit());
        assertEquals(ReservationStatus.HELD, query.getValue().status());
        assertEquals(Instant.parse("2026-10-05T10:00:00Z"), query.getValue().createdBefore());
    }

    @Test
    void aStatusTheContractDoesNotKnowIsA400() throws Exception {
        mockMvc.perform(read("/reservations").param("status", "CANCELLED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void aPageOutsideTheBoundsOfTheContractIsA400AndNotA500() throws Exception {
        mockMvc.perform(read("/reservations").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("the page starts at 1"));
    }

    @Test
    void aConfirmationAnswers200WithTheConfirmedReservation() throws Exception {
        Reservation confirmed = reservation().confirm(Instant.parse("2026-10-05T10:16:00Z"));
        when(useCases.confirmReservation(any(UUID.class), eq(confirmed.id()), any())).thenReturn(confirmed);

        mockMvc.perform(confirm(confirmed.id().toString(), KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(confirmed.id().toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

        verify(useCases).confirmReservation(UUID.fromString(SUB), confirmed.id(), CORRELATION);
    }

    @Test
    void aReservationThatCannotBeConfirmedIsA422WithTheTransitionCode() throws Exception {
        when(useCases.confirmReservation(any(UUID.class), any(UUID.class), any()))
                .thenThrow(new InvalidStatusTransitionException("the reservation cannot be confirmed from its current status"));

        mockMvc.perform(confirm(UUID.randomUUID().toString(), KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message").value("the reservation cannot be confirmed from its current status"))
                .andExpect(jsonPath("$.traceId").value(CORRELATION));
    }

    @Test
    void confirmingAnUnknownReservationIsA404AndSomebodyElsesIsA403() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(useCases.confirmReservation(any(UUID.class), eq(unknown), any()))
                .thenThrow(new ReservationNotFoundException(unknown));
        UUID foreign = UUID.randomUUID();
        when(useCases.confirmReservation(any(UUID.class), eq(foreign), any()))
                .thenThrow(new ReservationAccessDeniedException(foreign, UUID.fromString(SUB)));

        mockMvc.perform(confirm(unknown.toString(), KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mockMvc.perform(confirm(foreign.toString(), KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void anIdThatIsNotAUuidOrAMissingKeyIsA400() throws Exception {
        mockMvc.perform(confirm("not-a-uuid", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/reservations/" + UUID.randomUUID() + "/confirm")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    private static MockHttpServletRequestBuilder confirm(String reservationId, String idempotencyKey) {
        return post("/reservations/" + reservationId + "/confirm")
                .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION)
                .header("Idempotency-Key", idempotencyKey)
                .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB);
    }

    private static MockHttpServletRequestBuilder hold(String idempotencyKey, String body) {
        return post("/holds")
                .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION)
                .header("Idempotency-Key", idempotencyKey)
                .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MockHttpServletRequestBuilder read(String path) {
        return get(path)
                .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION)
                .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB);
    }

    private static Reservation reservation() {
        return Reservation.hold(UUID.randomUUID(), UUID.fromString(SUB), SHOWTIME, List.of("A1", "A2"),
                Duration.ofSeconds(600), Instant.parse("2026-10-05T10:15:30.123456Z"), "Movie", "Room 1", 0);
    }
}
