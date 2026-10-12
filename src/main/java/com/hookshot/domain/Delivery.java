package com.hookshot.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Delivery(
        UUID id,
        UUID endpointId,
        UUID eventId,
        DeliveryStatus status,
        int attempts,
        OffsetDateTime nextAttemptAt,
        Integer lastStatusCode,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
