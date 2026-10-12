package com.hookshot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.hookshot.service.EventService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/events")
public class EventController {

    public record PublishRequest(@NotBlank @Size(max = 100) String type, @NotNull JsonNode data) {}

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    /** 202 Accepted for a new event, 200 OK when the Idempotency-Key was already used. */
    @PostMapping
    public ResponseEntity<EventService.PublishResult> publish(
            @Valid @RequestBody PublishRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        if (request.data().isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data must not be null");
        }
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 255)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-255 characters");
        }
        EventService.PublishResult result = service.publish(request.type(), request.data(), idempotencyKey);
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(result);
    }
}
