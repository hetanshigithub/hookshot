package com.hookshot.service;

import com.hookshot.delivery.TargetValidator;
import com.hookshot.domain.Endpoint;
import com.hookshot.repo.EndpointRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EndpointService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EndpointRepository endpoints;
    private final TargetValidator validator;
    private final Clock clock;

    public EndpointService(EndpointRepository endpoints, TargetValidator validator, Clock clock) {
        this.endpoints = endpoints;
        this.validator = validator;
        this.clock = clock;
    }

    public Endpoint register(String url) {
        validator.validate(url);
        Endpoint endpoint = new Endpoint(UUID.randomUUID(), url, generateSecret(), true, OffsetDateTime.now(clock));
        return endpoints.insert(endpoint);
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
