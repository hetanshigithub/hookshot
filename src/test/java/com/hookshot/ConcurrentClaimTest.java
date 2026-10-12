package com.hookshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.hookshot.domain.ClaimedDelivery;
import com.hookshot.domain.Delivery;
import com.hookshot.domain.DeliveryStatus;
import com.hookshot.domain.Endpoint;
import com.hookshot.domain.Event;
import com.hookshot.repo.DeliveryRepository;
import com.hookshot.repo.EndpointRepository;
import com.hookshot.repo.EventRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The core guarantee of the queue: with many workers racing, every delivery is leased by exactly
 * one of them. This relies on {@code FOR UPDATE SKIP LOCKED}, so it only runs against real
 * PostgreSQL (the CI "postgres" job sets SPRING_DATASOURCE_URL).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".*postgresql.*")
class ConcurrentClaimTest {

    @Autowired JdbcClient jdbc;
    @Autowired DeliveryRepository deliveries;
    @Autowired EndpointRepository endpoints;
    @Autowired EventRepository events;

    @Test
    void concurrentWorkersNeverClaimTheSameDelivery() throws Exception {
        jdbc.sql("DELETE FROM deliveries").update();
        jdbc.sql("DELETE FROM events").update();
        jdbc.sql("DELETE FROM endpoints").update();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Endpoint endpoint = endpoints.insert(new Endpoint(UUID.randomUUID(), "http://example.com/hook", "secret", true, now));
        int total = 200;
        for (int i = 0; i < total; i++) {
            Event event = new Event(UUID.randomUUID(), "load.test", "{}", null, now);
            events.insert(event);
            deliveries.insert(new Delivery(
                    UUID.randomUUID(), endpoint.id(), event.id(), DeliveryStatus.PENDING, 0,
                    now.minusSeconds(1), null, null, now, now));
        }

        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<UUID>>> futures = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            futures.add(pool.submit(() -> {
                start.await();
                List<UUID> mine = new ArrayList<>();
                while (true) {
                    OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
                    List<ClaimedDelivery> batch = deliveries.claimDue(t, 7, t.plusMinutes(5));
                    if (batch.isEmpty()) {
                        break;
                    }
                    batch.forEach(c -> mine.add(c.id()));
                }
                return mine;
            }));
        }
        start.countDown();

        List<UUID> claimed = new ArrayList<>();
        for (Future<List<UUID>> future : futures) {
            claimed.addAll(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(claimed).as("every delivery claimed, none twice").hasSize(total).doesNotHaveDuplicates();
    }
}
