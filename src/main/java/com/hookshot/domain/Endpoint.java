package com.hookshot.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Endpoint(UUID id, String url, String secret, boolean active, OffsetDateTime createdAt) {}
