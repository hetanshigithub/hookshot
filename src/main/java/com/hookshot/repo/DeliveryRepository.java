package com.hookshot.repo;

import com.hookshot.domain.ClaimedDelivery;
import com.hookshot.domain.Delivery;
import com.hookshot.domain.DeliveryStatus;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deliveries table is the work queue. Workers claim rows with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}: concurrent workers skip rows another worker has
 * locked instead of blocking on them, so N instances can drain the queue without ever sending
 * the same delivery twice at the same time.
 */
@Repository
public class DeliveryRepository {

    private static final String COLUMNS = "id, endpoint_id, event_id, status, attempts, next_attempt_at, "
            + "last_status_code, last_error, created_at, updated_at";

    private static final RowMapper<Delivery> MAPPER = (ResultSet rs, int row) -> mapDelivery(rs);

    private static final RowMapper<ClaimedDelivery> CLAIMED_MAPPER = (ResultSet rs, int row) -> new ClaimedDelivery(
            rs.getObject("id", UUID.class),
            rs.getObject("endpoint_id", UUID.class),
            rs.getObject("event_id", UUID.class),
            rs.getInt("attempts"),
            rs.getString("url"),
            rs.getString("secret"),
            rs.getString("event_type"),
            rs.getString("payload"));

    private record StatusCount(String status, long count) {}

    private final JdbcClient jdbc;
    private final boolean skipLocked;

    public DeliveryRepository(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.skipLocked = isPostgres(dataSource);
    }

    public void insert(Delivery d) {
        jdbc.sql("INSERT INTO deliveries (id, endpoint_id, event_id, status, attempts, next_attempt_at, "
                        + "last_status_code, last_error, created_at, updated_at) "
                        + "VALUES (:id, :endpointId, :eventId, :status, :attempts, :next, :code, :error, :createdAt, :updatedAt)")
                .param("id", d.id())
                .param("endpointId", d.endpointId())
                .param("eventId", d.eventId())
                .param("status", d.status().name())
                .param("attempts", d.attempts())
                .param("next", d.nextAttemptAt())
                .param("code", d.lastStatusCode(), Types.INTEGER)
                .param("error", d.lastError(), Types.VARCHAR)
                .param("createdAt", d.createdAt())
                .param("updatedAt", d.updatedAt())
                .update();
    }

    /**
     * Atomically leases up to {@code limit} due deliveries. Rows are marked IN_FLIGHT and their
     * next_attempt_at is pushed to {@code leaseUntil}; if the worker dies mid-send, the row becomes
     * due again once the lease expires and another worker picks it up (at-least-once delivery).
     */
    @Transactional
    public List<ClaimedDelivery> claimDue(OffsetDateTime now, int limit, OffsetDateTime leaseUntil) {
        String lock = skipLocked ? " FOR UPDATE SKIP LOCKED" : " FOR UPDATE";
        List<UUID> ids = jdbc.sql("SELECT id FROM deliveries "
                        + "WHERE status IN ('PENDING', 'FAILED', 'IN_FLIGHT') AND next_attempt_at <= :now "
                        + "ORDER BY next_attempt_at LIMIT :limit" + lock)
                .param("now", now)
                .param("limit", limit)
                .query((ResultSet rs, int row) -> rs.getObject("id", UUID.class))
                .list();
        if (ids.isEmpty()) {
            return List.of();
        }
        jdbc.sql("UPDATE deliveries SET status = 'IN_FLIGHT', next_attempt_at = :lease, updated_at = :now "
                        + "WHERE id IN (:ids)")
                .param("lease", leaseUntil)
                .param("now", now)
                .param("ids", ids)
                .update();
        return jdbc.sql("SELECT d.id, d.endpoint_id, d.event_id, d.attempts, en.url, en.secret, ev.event_type, ev.payload "
                        + "FROM deliveries d "
                        + "JOIN endpoints en ON en.id = d.endpoint_id "
                        + "JOIN events ev ON ev.id = d.event_id "
                        + "WHERE d.id IN (:ids) ORDER BY d.created_at, d.id")
                .param("ids", ids)
                .query(CLAIMED_MAPPER)
                .list();
    }

    public void recordAttempt(
            UUID id,
            DeliveryStatus status,
            int attempts,
            Integer statusCode,
            String error,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime now) {
        jdbc.sql("UPDATE deliveries SET status = :status, attempts = :attempts, last_status_code = :code, "
                        + "last_error = :error, next_attempt_at = :next, updated_at = :now WHERE id = :id")
                .param("status", status.name())
                .param("attempts", attempts)
                .param("code", statusCode, Types.INTEGER)
                .param("error", error, Types.VARCHAR)
                .param("next", nextAttemptAt)
                .param("now", now)
                .param("id", id)
                .update();
    }

    public Optional<Delivery> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM deliveries WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public List<Delivery> list(DeliveryStatus status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM deliveries");
        if (status != null) {
            sql.append(" WHERE status = :status");
        }
        sql.append(" ORDER BY created_at DESC, id LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        return spec.query(MAPPER).list();
    }

    public int countForEvent(UUID eventId) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM deliveries WHERE event_id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    /** Re-queues a DEAD or FAILED delivery. Returns the number of rows changed (0 = not eligible). */
    public int requeue(UUID id, OffsetDateTime now) {
        return jdbc.sql("UPDATE deliveries SET status = 'PENDING', attempts = 0, next_attempt_at = :now, "
                        + "last_status_code = NULL, last_error = NULL, updated_at = :now "
                        + "WHERE id = :id AND status IN ('DEAD', 'FAILED')")
                .param("now", now)
                .param("id", id)
                .update();
    }

    public Map<DeliveryStatus, Long> countByStatus() {
        Map<DeliveryStatus, Long> counts = new EnumMap<>(DeliveryStatus.class);
        for (DeliveryStatus s : DeliveryStatus.values()) {
            counts.put(s, 0L);
        }
        List<StatusCount> rows = jdbc.sql("SELECT status, COUNT(*) AS n FROM deliveries GROUP BY status")
                .query((ResultSet rs, int row) -> new StatusCount(rs.getString("status"), rs.getLong("n")))
                .list();
        for (StatusCount row : rows) {
            counts.put(DeliveryStatus.valueOf(row.status()), row.count());
        }
        return counts;
    }

    private static Delivery mapDelivery(ResultSet rs) throws SQLException {
        int code = rs.getInt("last_status_code");
        Integer statusCode = rs.wasNull() ? null : code;
        return new Delivery(
                rs.getObject("id", UUID.class),
                rs.getObject("endpoint_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                DeliveryStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getObject("next_attempt_at", OffsetDateTime.class),
                statusCode,
                rs.getString("last_error"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot inspect database", e);
        }
    }
}
