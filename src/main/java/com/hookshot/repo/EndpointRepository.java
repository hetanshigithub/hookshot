package com.hookshot.repo;

import com.hookshot.domain.Endpoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EndpointRepository {

    private static final RowMapper<Endpoint> MAPPER = (ResultSet rs, int row) -> mapRow(rs);

    private final JdbcClient jdbc;

    public EndpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Endpoint insert(Endpoint endpoint) {
        jdbc.sql("INSERT INTO endpoints (id, url, secret, active, created_at) "
                        + "VALUES (:id, :url, :secret, :active, :createdAt)")
                .param("id", endpoint.id())
                .param("url", endpoint.url())
                .param("secret", endpoint.secret())
                .param("active", endpoint.active())
                .param("createdAt", endpoint.createdAt())
                .update();
        return endpoint;
    }

    public List<Endpoint> findActive() {
        return jdbc.sql("SELECT id, url, secret, active, created_at FROM endpoints "
                        + "WHERE active = TRUE ORDER BY created_at, id")
                .query(MAPPER)
                .list();
    }

    private static Endpoint mapRow(ResultSet rs) throws SQLException {
        return new Endpoint(
                rs.getObject("id", UUID.class),
                rs.getString("url"),
                rs.getString("secret"),
                rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
