package com.hookshot.config;

import com.hookshot.delivery.BackoffPolicy;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** Injected everywhere instead of calling Instant.now(), so tests can control time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public BackoffPolicy backoffPolicy(HookshotProperties properties) {
        return new BackoffPolicy(
                properties.baseDelay(), properties.maxDelay(), () -> ThreadLocalRandom.current().nextDouble());
    }
}
