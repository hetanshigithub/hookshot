package com.hookshot.domain;

import java.util.UUID;

/** A delivery a worker has leased, joined with everything needed to send it. */
public record ClaimedDelivery(
        UUID id, UUID endpointId, UUID eventId, int attempts, String url, String secret, String eventType, String payload) {}
