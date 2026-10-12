package com.hookshot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "hookshot")
public record HookshotProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("5s") Duration baseDelay,
        @DefaultValue("1h") Duration maxDelay,
        @DefaultValue("false") boolean allowPrivateTargets,
        @DefaultValue Worker worker) {

    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20") int batchSize,
            @DefaultValue("60s") Duration lease,
            @DefaultValue("5s") Duration httpTimeout) {}
}
