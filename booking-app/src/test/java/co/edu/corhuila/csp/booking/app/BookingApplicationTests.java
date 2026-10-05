package co.edu.corhuila.csp.booking.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import co.edu.corhuila.csp.booking.adapter.in.http.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void theCompositionRootStarts() {
        assertNotNull(context.getBean(BookingApplication.class));
    }

    @Test
    void theAdaptersArePartOfTheSameContext() {
        assertNotNull(context.getBean(CorrelationIdFilter.class));
    }
}
