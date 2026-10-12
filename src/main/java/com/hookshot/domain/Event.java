package com.hookshot.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Event(UUID id, String type, String payload, String idempotencyKey, OffsetDateTime createdAt) {}
