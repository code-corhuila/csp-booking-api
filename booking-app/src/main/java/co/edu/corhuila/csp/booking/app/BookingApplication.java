package co.edu.corhuila.csp.booking.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Composition root of the booking service. This module is the only one that knows every concrete type
 * and every limit; the adapters and the core never read configuration by themselves.
 */
@SpringBootApplication
public class BookingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingApplication.class, args);
    }
}
