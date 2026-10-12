package com.hookshot.web;

import com.hookshot.domain.Delivery;
import com.hookshot.domain.DeliveryStatus;
import com.hookshot.repo.DeliveryRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DeliveryController {

    /** successRate = succeeded / (succeeded + dead); null until at least one delivery has finished. */
    public record Stats(Map<String, Long> counts, Double successRate) {}

    private final DeliveryRepository deliveries;
    private final Clock clock;

    public DeliveryController(DeliveryRepository deliveries, Clock clock) {
        this.deliveries = deliveries;
        this.clock = clock;
    }

    @GetMapping("/deliveries")
    public List<Delivery> list(
            @RequestParam(name = "status", required = false) DeliveryStatus status,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return deliveries.list(status, Math.max(1, Math.min(limit, 200)));
    }

    @PostMapping("/deliveries/{id}/redeliver")
    public ResponseEntity<Delivery> redeliver(@PathVariable("id") UUID id) {
        if (deliveries.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found");
        }
        if (deliveries.requeue(id, OffsetDateTime.now(clock)) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only FAILED or DEAD deliveries can be redelivered");
        }
        Delivery requeued = deliveries.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found"));
        return ResponseEntity.accepted().body(requeued);
    }

    @GetMapping("/stats")
    public Stats stats() {
        Map<DeliveryStatus, Long> byStatus = deliveries.countByStatus();
        Map<String, Long> counts = new LinkedHashMap<>();
        byStatus.forEach((status, count) -> counts.put(status.name(), count));
        long succeeded = byStatus.get(DeliveryStatus.SUCCEEDED);
        long finished = succeeded + byStatus.get(DeliveryStatus.DEAD);
        Double rate = finished == 0 ? null : (double) succeeded / finished;
        return new Stats(counts, rate);
    }
}
