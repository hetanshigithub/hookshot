package com.hookshot.repo;

import com.hookshot.domain.Event;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

    private static final RowMapper<Event> MAPPER = (ResultSet rs, int row) -> mapRow(rs);

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws org.springframework.dao.DuplicateKeyException if the idempotency key was already used */
    public void insert(Event event) {
        jdbc.sql("INSERT INTO events (id, event_type, payload, idempotency_key, created_at) "
                        + "VALUES (:id, :type, :payload, :key, :createdAt)")
                .param("id", event.id())
                .param("type", event.type())
                .param("payload", event.payload())
                .param("key", event.idempotencyKey(), Types.VARCHAR)
                .param("createdAt", event.createdAt())
                .update();
    }

    public Optional<Event> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT id, event_type, payload, idempotency_key, created_at FROM events "
                        + "WHERE idempotency_key = :key")
                .param("key", key)
                .query(MAPPER)
                .optional();
    }

    private static Event mapRow(ResultSet rs) throws SQLException {
        return new Event(
                rs.getObject("id", UUID.class),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getString("idempotency_key"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
