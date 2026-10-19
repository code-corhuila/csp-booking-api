package co.edu.corhuila.csp.booking.adapter.in.http;

import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.csp.booking.application.port.in.CreateHoldInput;
import co.edu.corhuila.csp.booking.application.port.in.ReservationUseCases;
import co.edu.corhuila.csp.booking.application.port.out.CreateHoldResult;
import co.edu.corhuila.csp.booking.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.booking.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.booking.domain.model.Reservation;
import co.edu.corhuila.csp.booking.domain.model.ReservationNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The workflow operations of {@code booking-service.yaml}: who may call them is the filter's job (ADR-035). */
class WorkflowControllerTest {

    private static final String OWNER = "11111111-1111-4111-8111-111111111111";
    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";
    private static final String KEY = "6b1f0d2e-4a3c-4f1a-9c2d-8e7f6a5b4c3d:hold";
    private static final UUID SHOWTIME = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String BODY = """
            {"userId":"11111111-1111-4111-8111-111111111111",\
            "showtimeId":"33333333-3333-3333-3333-333333333333","seatLabels":["A1","A2"],\
            "movieTitle":"Movie","roomName":"Room 1"}""";

    private ReservationUseCases useCases;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCases = mock(ReservationUseCases.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkflowController(useCases))
                .addFilters(new CorrelationIdFilter())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void aHoldForTheUserOfTheBodyAnswers201WithTheLocation() throws Exception {
        Reservation created = reservation();
        when(useCases.createHold(any())).thenReturn(new CreateHoldResult.Created(created));

        mockMvc.perform(hold(KEY, BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/reservations/" + created.id())))
                .andExpect(jsonPath("$.userId").value(OWNER))
                .andExpect(jsonPath("$.status").value("HELD"));

        ArgumentCaptor<CreateHoldInput> input = ArgumentCaptor.forClass(CreateHoldInput.class);
        verify(useCases).createHold(input.capture());
        assertEquals(UUID.fromString(OWNER), input.getValue().userId());
        assertEquals(KEY, input.getValue().idempotencyKey());
        assertEquals(CORRELATION, input.getValue().correlationId());
        assertEquals(600, input.getValue().holdDurationSeconds());
    }

    @Test
    void aReplayedKeyAnswers200WithTheOriginalReservation() throws Exception {
        Reservation original = reservation();
        when(useCases.createHold(any())).thenReturn(new CreateHoldResult.Replayed(original));

        mockMvc.perform(hold(KEY, BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(original.id().toString()));
    }

    @Test
    void aKeyUsedWithAnotherPayloadAnswers409() throws Exception {
        when(useCases.createHold(any())).thenThrow(new IdempotencyKeyConflictException());

        mockMvc.perform(hold(KEY, BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void aHoldWithoutTheUserOrWithAnIdThatIsNotAUuidIsA400() throws Exception {
        mockMvc.perform(hold(KEY, BODY.replace("\"userId\":\"" + OWNER + "\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mockMvc.perform(hold(KEY, BODY.replace(OWNER, "not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        verifyNoInteractions(useCases);
    }

    @Test
    void aConfirmationAnswers200AndTakesNoUserBecauseTheOwnerIsTheStoredOne() throws Exception {
        Reservation confirmed = reservation().confirm(Instant.parse("2026-10-05T10:16:00Z"));
        when(useCases.confirmReservationForWorkflow(eq(confirmed.id()), any())).thenReturn(confirmed);

        mockMvc.perform(confirm(confirmed.id().toString(), KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

        verify(useCases).confirmReservationForWorkflow(confirmed.id(), CORRELATION);
    }

    @Test
    void anUnknownReservationIs404AndAnExpiredOneIs422() throws Exception {
        UUID unknown = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        when(useCases.confirmReservationForWorkflow(eq(unknown), any()))
                .thenThrow(new ReservationNotFoundException(unknown));
        when(useCases.confirmReservationForWorkflow(eq(expired), any()))
                .thenThrow(new InvalidStatusTransitionException("the reservation cannot be confirmed"));

        mockMvc.perform(confirm(unknown.toString(), KEY)).andExpect(status().isNotFound());
        mockMvc.perform(confirm(expired.toString(), KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void anIdThatIsNotAUuidOrAMissingKeyIsA400() throws Exception {
        mockMvc.perform(confirm("not-a-uuid", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/internal/workflow/reservations/" + UUID.randomUUID() + "/confirm"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        verifyNoInteractions(useCases);
    }

    private static MockHttpServletRequestBuilder confirm(String reservationId, String idempotencyKey) {
        return post("/internal/workflow/reservations/" + reservationId + "/confirm")
                .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION)
                .header("Idempotency-Key", idempotencyKey);
    }

    private static MockHttpServletRequestBuilder hold(String idempotencyKey, String body) {
        return post("/internal/workflow/holds")
                .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static Reservation reservation() {
        return Reservation.hold(UUID.randomUUID(), UUID.fromString(OWNER), SHOWTIME, List.of("A1", "A2"),
                Duration.ofSeconds(600), Instant.parse("2026-10-05T10:15:30.123456Z"), "Movie", "Room 1", 0);
    }
}
