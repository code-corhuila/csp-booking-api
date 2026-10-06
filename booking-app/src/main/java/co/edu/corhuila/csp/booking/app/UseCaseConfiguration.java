package co.edu.corhuila.csp.booking.app;

import co.edu.corhuila.csp.booking.application.port.in.ReservationUseCases;
import co.edu.corhuila.csp.booking.application.port.out.HoldRepository;
import co.edu.corhuila.csp.booking.application.usecase.ReservationService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where the use cases meet their ports (Anexo C, composition root). Nothing in the application
 * reads the wall time by itself: the hold takes it from this clock, so a test can freeze it.
 */
@Configuration
public class UseCaseConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ReservationUseCases reservationUseCases(HoldRepository holds, Clock clock) {
        return new ReservationService(holds, clock);
    }
}
