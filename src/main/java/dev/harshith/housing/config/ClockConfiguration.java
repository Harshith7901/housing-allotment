package dev.harshith.housing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The clock is a bean.
 *
 * <p>Not ceremony: timestamps here are hashed into the audit chain and into the frozen
 * roll, and tests have to be able to assert on exact hash values. A service that calls
 * {@code Instant.now()} directly cannot be tested for reproducibility, which is the one
 * property this system most needs tested. Injecting the clock also makes the boundary
 * explicit — the core has no clock at all, and every timestamp enters the system here.
 *
 * <p>UTC, always. A scheme whose roll hash depends on the server's time zone would be
 * indefensible, and daylight-saving transitions are not a risk anybody should accept in
 * an audit log.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
