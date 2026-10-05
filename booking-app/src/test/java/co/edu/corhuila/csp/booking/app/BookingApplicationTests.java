package co.edu.corhuila.csp.booking.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import co.edu.corhuila.csp.booking.adapter.in.http.CorrelationIdFilter;
import co.edu.corhuila.csp.booking.adapter.in.http.JwtAuthenticationFilter;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingApplicationTests {

    /** The key of this context is generated, never committed: it stands for JWT_PUBLIC_KEY. */
    private static final RsaTestKey JWT_KEY = RsaTestKey.generate();

    @DynamicPropertySource
    static void jwtKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", JWT_KEY::publicKeyPem);
    }

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

    @Test
    void theServiceValidatesTheTokenWithItsOwnPublicKey() {
        assertNotNull(context.getBean(JwtAuthenticationFilter.class));
        assertNotNull(context.getBean(RSAPublicKey.class));
    }
}
