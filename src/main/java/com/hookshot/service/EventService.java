package com.hookshot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hookshot.domain.Delivery;
import com.hookshot.domain.DeliveryStatus;
import com.hookshot.domain.Endpoint;
import com.hookshot.domain.Event;
import com.hookshot.repo.DeliveryRepository;
import com.hookshot.repo.EndpointRepository;
import com.hookshot.repo.EventRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EventService {

    public record PublishResult(UUID eventId, int deliveries, boolean duplicate) {}

    private final EventRepository events;
    private final EndpointRepository endpoints;
    private final DeliveryRepository deliveries;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Clock clock;

    public EventService(
            EventRepository events,
            EndpointRepository endpoints,
            DeliveryRepository deliveries,
            TransactionTemplate tx,
            ObjectMapper mapper,
            Clock clock) {
        this.events = events;
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.tx = tx;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Stores the event and fans it out to every active endpoint in one transaction. If the same
     * idempotency key is published again (a client retry, a double click), the original result is
     * returned and nothing new is queued.
     */
    public PublishResult publish(String type, JsonNode data, String idempotencyKey) {
        if (idempotencyKey != null) {
            Event existing = events.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null) {
                return duplicateOf(existing);
            }
        }
        String payload = serialize(data);
        try {
            return tx.execute(status -> create(type, payload, idempotencyKey));
        } catch (DuplicateKeyException race) {
            // Two requests with the same key raced past the check above; the unique constraint decided.
            // The failed transaction is rolled back before we get here, so querying again is safe.
            if (idempotencyKey == null) {
                throw race;
            }
            return events.findByIdempotencyKey(idempotencyKey).map(this::duplicateOf).orElseThrow(() -> race);
        }
    }

    private PublishResult create(String type, String payload, String idempotencyKey) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Event event = new Event(UUID.randomUUID(), type, payload, idempotencyKey, now);
        events.insert(event);
        List<Endpoint> targets = endpoints.findActive();
        for (Endpoint target : targets) {
            deliveries.insert(new Delivery(
                    UUID.randomUUID(), target.id(), event.id(), DeliveryStatus.PENDING, 0, now, null, null, now, now));
        }
        return new PublishResult(event.id(), targets.size(), false);
    }

    private PublishResult duplicateOf(Event event) {
        return new PublishResult(event.id(), deliveries.countForEvent(event.id()), true);
    }

    private String serialize(JsonNode data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event data is not serializable", e);
        }
    }
}
