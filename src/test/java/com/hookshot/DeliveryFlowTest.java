package com.hookshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hookshot.delivery.DeliveryWorker;
import com.hookshot.delivery.Signatures;
import com.hookshot.domain.ClaimedDelivery;
import com.hookshot.repo.DeliveryRepository;
import com.hookshot.support.MutableClock;
import com.hookshot.support.TestClockConfig;
import com.hookshot.support.TestReceiver;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** End-to-end: HTTP API -> database queue -> worker -> real HTTP receiver, with a controllable clock. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class DeliveryFlowTest {

    private static final String EVENT_JSON = "{\"type\":\"order.created\",\"data\":{\"orderId\":42}}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DeliveryWorker worker;
    @Autowired DeliveryRepository deliveryRepository;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;

    private TestReceiver receiver;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM deliveries").update();
        jdbc.sql("DELETE FROM events").update();
        jdbc.sql("DELETE FROM endpoints").update();
        clock.set(TestClockConfig.START);
        receiver = new TestReceiver();
    }

    @AfterEach
    void tearDown() {
        receiver.close();
    }

    // ---------------------------------------------------------------- helpers

    private String registerEndpoint() throws Exception {
        MvcResult result = mvc.perform(post("/api/endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("url", receiver.url()))))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("secret").asText();
    }

    private JsonNode publish(String idempotencyKey, int expectedStatus) throws Exception {
        var request = post("/api/events").contentType(MediaType.APPLICATION_JSON).content(EVENT_JSON);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        MvcResult result = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode deliveries() throws Exception {
        MvcResult result = mvc.perform(get("/api/deliveries")).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode onlyDelivery() throws Exception {
        JsonNode all = deliveries();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    // ---------------------------------------------------------------- tests

    @Test
    void deliversSignedPayloadToEndpoint() throws Exception {
        String secret = registerEndpoint();
        JsonNode published = publish(null, 202);
        assertThat(published.get("deliveries").asInt()).isEqualTo(1);
        assertThat(published.get("duplicate").asBoolean()).isFalse();

        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(receiver.requests()).hasSize(1);
        TestReceiver.Received received = receiver.requests().get(0);
        assertThat(Signatures.verify(
                        secret,
                        received.headers().get("x-hookshot-signature"),
                        received.body(),
                        clock.instant().getEpochSecond(),
                        300))
                .as("receiver can verify the signature").isTrue();
        assertThat(received.headers().get("x-hookshot-attempt")).isEqualTo("1");
        assertThat(received.headers().get("x-hookshot-event")).isEqualTo(published.get("eventId").asText());

        JsonNode body = mapper.readTree(received.body());
        assertThat(body.get("type").asText()).isEqualTo("order.created");
        assertThat(body.get("data").get("orderId").asInt()).isEqualTo(42);

        JsonNode delivery = onlyDelivery();
        assertThat(delivery.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(delivery.get("attempts").asInt()).isEqualTo(1);
        assertThat(delivery.get("lastStatusCode").asInt()).isEqualTo(200);
    }

    @Test
    void fansOutToEveryActiveEndpoint() throws Exception {
        registerEndpoint();
        registerEndpoint();
        assertThat(publish(null, 202).get("deliveries").asInt()).isEqualTo(2);

        assertThat(worker.runOnce()).isEqualTo(2);
        assertThat(receiver.requests()).hasSize(2);
    }

    @Test
    void retriesWithBackoffThenDeadLetters() throws Exception {
        registerEndpoint();
        receiver.respondWith(500);
        publish(null, 202);

        // Attempt 1 fails and is scheduled for a later retry.
        worker.runOnce();
        assertThat(receiver.requests()).hasSize(1);
        JsonNode afterFirst = onlyDelivery();
        assertThat(afterFirst.get("status").asText()).isEqualTo("FAILED");
        assertThat(afterFirst.get("attempts").asInt()).isEqualTo(1);
        assertThat(afterFirst.get("lastStatusCode").asInt()).isEqualTo(500);
        assertThat(OffsetDateTime.parse(afterFirst.get("nextAttemptAt").asText()))
                .as("retry is scheduled in the future")
                .isAfter(OffsetDateTime.parse(TestClockConfig.START.toString()));

        // Not due yet: running the worker again must not send anything.
        assertThat(worker.runOnce()).isZero();
        assertThat(receiver.requests()).hasSize(1);

        clock.advance(Duration.ofHours(1));
        worker.runOnce();
        assertThat(receiver.requests()).hasSize(2);
        assertThat(onlyDelivery().get("status").asText()).isEqualTo("FAILED");

        // Third failed attempt reaches max-attempts (3 in the test profile): dead-lettered.
        clock.advance(Duration.ofHours(1));
        worker.runOnce();
        assertThat(receiver.requests()).hasSize(3);
        JsonNode dead = onlyDelivery();
        assertThat(dead.get("status").asText()).isEqualTo("DEAD");
        assertThat(dead.get("attempts").asInt()).isEqualTo(3);

        // Dead deliveries are never retried automatically.
        clock.advance(Duration.ofHours(24));
        assertThat(worker.runOnce()).isZero();
        assertThat(receiver.requests()).hasSize(3);
    }

    @Test
    void goneResponseDeadLettersImmediately() throws Exception {
        registerEndpoint();
        receiver.respondWith(410);
        publish(null, 202);

        worker.runOnce();

        JsonNode delivery = onlyDelivery();
        assertThat(delivery.get("status").asText()).isEqualTo("DEAD");
        assertThat(delivery.get("attempts").asInt()).isEqualTo(1);
    }

    @Test
    void duplicateIdempotencyKeyDoesNotCreateAnotherEvent() throws Exception {
        registerEndpoint();
        JsonNode first = publish("order-42", 202);
        JsonNode second = publish("order-42", 200);

        assertThat(second.get("eventId").asText()).isEqualTo(first.get("eventId").asText());
        assertThat(second.get("duplicate").asBoolean()).isTrue();
        assertThat(second.get("deliveries").asInt()).isEqualTo(1);

        worker.runOnce();
        assertThat(receiver.requests()).as("delivered exactly once").hasSize(1);
    }

    @Test
    void redeliverRequeuesDeadDeliveries() throws Exception {
        registerEndpoint();
        receiver.respondWith(410);
        publish(null, 202);
        worker.runOnce();
        String id = onlyDelivery().get("id").asText();
        assertThat(onlyDelivery().get("status").asText()).isEqualTo("DEAD");

        receiver.respondWith(200);
        mvc.perform(post("/api/deliveries/" + id + "/redeliver")).andExpect(status().isAccepted());
        assertThat(onlyDelivery().get("status").asText()).isEqualTo("PENDING");

        worker.runOnce();
        assertThat(onlyDelivery().get("status").asText()).isEqualTo("SUCCEEDED");

        // A finished delivery cannot be redelivered; an unknown one is a 404.
        mvc.perform(post("/api/deliveries/" + id + "/redeliver")).andExpect(status().isConflict());
        mvc.perform(post("/api/deliveries/" + java.util.UUID.randomUUID() + "/redeliver"))
                .andExpect(status().isNotFound());
    }

    @Test
    void crashedWorkersLeaseExpiresAndDeliveryIsPickedUpAgain() throws Exception {
        registerEndpoint();
        publish(null, 202);

        // Simulate a worker that leased the delivery and then died before sending it.
        OffsetDateTime now = OffsetDateTime.parse(TestClockConfig.START.toString());
        List<ClaimedDelivery> claimed = deliveryRepository.claimDue(now, 10, now.plusSeconds(60));
        assertThat(claimed).hasSize(1);

        // While the lease is held, other workers leave it alone.
        assertThat(worker.runOnce()).isZero();
        assertThat(receiver.requests()).isEmpty();

        // After the lease expires it is claimable again.
        clock.advance(Duration.ofSeconds(61));
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(receiver.requests()).hasSize(1);
        assertThat(onlyDelivery().get("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void statsReportCountsAndSuccessRate() throws Exception {
        registerEndpoint();
        publish(null, 202);
        worker.runOnce();

        MvcResult result = mvc.perform(get("/api/stats")).andExpect(status().isOk()).andReturn();
        JsonNode stats = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(stats.get("counts").get("SUCCEEDED").asInt()).isEqualTo(1);
        assertThat(stats.get("counts").get("DEAD").asInt()).isZero();
        assertThat(stats.get("successRate").asDouble()).isEqualTo(1.0);
    }

    @Test
    void validatesRequests() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("{\"data\":{}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"x\",\"data\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/endpoints").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/endpoints").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"ftp://example.com\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/deliveries").param("status", "NOT_A_STATUS")).andExpect(status().isBadRequest());
    }
}
