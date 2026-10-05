package co.edu.corhuila.csp.booking.adapter.in.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The two operational routes of {@code booking-service.yaml}, with their contract answers. */
class HealthControllerTest {

    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";

    private DataSource dataSource;
    private Connection connection;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        this.dataSource = mock(DataSource.class);
        this.connection = mock(Connection.class);
        this.mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(dataSource))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void livenessAnswersOkWhateverTheStateOfTheDependencies() throws Exception {
        mockMvc.perform(get("/health").header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void readinessAnswersReadyWhenPostgresAnswersThePool() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        mockMvc.perform(get("/health/ready").header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.dependencies.postgres").value("connected"));
    }

    @Test
    void readinessAnswersTheEnvelopeOfTheContractWhenPostgresCannotBeReached() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        mockMvc.perform(get("/health/ready").header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").value(CORRELATION));
    }

    @Test
    void readinessAnswersTheEnvelopeOfTheContractWhenThePoolIsExhausted() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false);

        mockMvc.perform(get("/health/ready").header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }
}
