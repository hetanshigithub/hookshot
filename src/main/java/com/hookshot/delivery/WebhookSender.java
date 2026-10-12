package com.hookshot.delivery;

import com.hookshot.config.HookshotProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Performs a single signed HTTP POST. Never throws: every failure becomes a {@link DeliveryResult}. */
@Component
public class WebhookSender {

    private final HttpClient client;
    private final Duration requestTimeout;
    private final TargetValidator validator;

    public WebhookSender(HookshotProperties properties, TargetValidator validator) {
        this.requestTimeout = properties.worker().httpTimeout();
        this.validator = validator;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.worker().httpTimeout())
                // Never follow redirects: a public URL could redirect us to an internal address.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public DeliveryResult send(
            String url, String secret, String body, UUID deliveryId, UUID eventId, int attempt, long timestamp) {
        try {
            validator.validate(url);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Hookshot/1.0")
                    .header("X-Hookshot-Signature", Signatures.header(secret, timestamp, body))
                    .header("X-Hookshot-Delivery", deliveryId.toString())
                    .header("X-Hookshot-Event", eventId.toString())
                    .header("X-Hookshot-Attempt", Integer.toString(attempt))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return new DeliveryResult(status, status >= 200 && status < 300 ? null : "HTTP " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeliveryResult(null, "Interrupted");
        } catch (IOException | RuntimeException e) {
            return new DeliveryResult(null, truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
