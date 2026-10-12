package com.hookshot.web;

import com.hookshot.domain.Endpoint;
import com.hookshot.service.EndpointService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/endpoints")
public class EndpointController {

    public record CreateEndpointRequest(@NotBlank @Size(max = 2048) String url) {}

    /** The signing secret is returned exactly once, at creation. */
    public record EndpointResponse(UUID id, String url, String secret, boolean active, OffsetDateTime createdAt) {}

    private final EndpointService service;

    public EndpointController(EndpointService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EndpointResponse create(@Valid @RequestBody CreateEndpointRequest request) {
        Endpoint e = service.register(request.url());
        return new EndpointResponse(e.id(), e.url(), e.secret(), e.active(), e.createdAt());
    }
}
