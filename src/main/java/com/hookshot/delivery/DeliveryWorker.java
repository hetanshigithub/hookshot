package com.hookshot.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hookshot.config.HookshotProperties;
import com.hookshot.domain.ClaimedDelivery;
import com.hookshot.domain.DeliveryStatus;
import com.hookshot.repo.DeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the delivery queue. Each poll leases a batch of due deliveries, sends them concurrently
 * on virtual threads, then records the outcome: success, scheduled retry, or dead-letter.
 * Run as many instances as you like; the lease + SKIP LOCKED claim keeps them from colliding.
 */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryRepository deliveries;
    private final WebhookSender sender;
    private final BackoffPolicy backoff;
    private final HookshotProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MeterRegistry metrics;

    public DeliveryWorker(
            DeliveryRepository deliveries,
            WebhookSender sender,
            BackoffPolicy backoff,
            HookshotProperties properties,
            ObjectMapper mapper,
            Clock clock,
            MeterRegistry metrics) {
        this.deliveries = deliveries;
        this.sender = sender;
        this.backoff = backoff;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${hookshot.worker.poll-interval-ms:1000}")
    public void poll() {
        if (!properties.worker().enabled()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Delivery poll failed", e);
        }
    }

    /** Processes one batch of due deliveries and returns how many were claimed. */
    public int runOnce() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        HookshotProperties.Worker worker = properties.worker();
        List<ClaimedDelivery> batch = deliveries.claimDue(now, worker.batchSize(), now.plus(worker.lease()));
        if (batch.isEmpty()) {
            return 0;
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ClaimedDelivery claimed : batch) {
                pool.submit(() -> processSafely(claimed));
            }
        } // close() waits for all submitted tasks to finish
        return batch.size();
    }

    private void processSafely(ClaimedDelivery claimed) {
        try {
            process(claimed);
        } catch (RuntimeException e) {
            // The row stays IN_FLIGHT; its lease will expire and it will be retried.
            log.error("Failed to process delivery {}", claimed.id(), e);
        }
    }

    private void process(ClaimedDelivery claimed) {
        int attempt = claimed.attempts() + 1;
        long timestamp = clock.instant().getEpochSecond();
        DeliveryResult result = sender.send(
                claimed.url(), claimed.secret(), envelope(claimed), claimed.id(), claimed.eventId(), attempt, timestamp);

        OffsetDateTime now = OffsetDateTime.now(clock);
        String outcome;
        if (result.success()) {
            deliveries.recordAttempt(claimed.id(), DeliveryStatus.SUCCEEDED, attempt, result.statusCode(), null, now, now);
            outcome = "success";
        } else if (result.permanentFailure() || attempt >= properties.maxAttempts()) {
            deliveries.recordAttempt(
                    claimed.id(), DeliveryStatus.DEAD, attempt, result.statusCode(), result.error(), now, now);
            outcome = "dead";
            log.warn("Delivery {} is dead after {} attempt(s): {}", claimed.id(), attempt, result.error());
        } else {
            OffsetDateTime next = now.plus(backoff.delayForAttempt(attempt));
            deliveries.recordAttempt(
                    claimed.id(), DeliveryStatus.FAILED, attempt, result.statusCode(), result.error(), next, now);
            outcome = "retry";
        }
        metrics.counter("hookshot.delivery.attempts", "outcome", outcome).increment();
    }

    /** The exact string we sign and send: {"id":..., "type":..., "data":{...}}. */
    private String envelope(ClaimedDelivery claimed) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", claimed.eventId().toString());
            node.put("type", claimed.eventType());
            node.set("data", mapper.readTree(claimed.payload()));
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored payload is not valid JSON", e);
        }
    }
}
