package com.github.obhen233.core.database;

import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.IdentifierGenerator;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/**
 * A custom ID generator that uses the <strong>current session's connection</strong>
 * to allocate IDs from a {@code hibernate_sequences} table, avoiding
 * Hibernate's {@code JdbcIsolationDelegate} (which is incompatible with SQLite's
 * WAL mode and multi-connection pool).
 * <p>
 * Uses a CAS (compare-and-swap) loop for concurrency safety within the same
 * connection, and lazy initialization of the sequence row.
 * <p>
 * Accepts {@code @Parameter} annotations:
 * <ul>
 *   <li>{@code table_name} — defaults to {@code hibernate_sequences}</li>
 *   <li>{@code segment_value} — defaults to {@code default}</li>
 * </ul>
 */
public class DiatomIdGenerator implements IdentifierGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DiatomIdGenerator.class);
    private static final long INITIAL_VALUE = 1L;

    private String tableName = "hibernate_sequences";
    private String segmentValue = "default";
    private Class<?> idType = Long.class;

    @Override
    public void configure(Type type, Properties params, ServiceRegistry serviceRegistry) {
        if (type != null) {
            idType = type.getReturnedClass();
        }
        if (params.containsKey("table_name")) {
            tableName = params.getProperty("table_name");
        }
        if (params.containsKey("segment_value")) {
            segmentValue = params.getProperty("segment_value");
        }
        logger.debug("DiatomIdGenerator configured: table={}, segment={}, idType={}",
                tableName, segmentValue, idType.getSimpleName());
    }

    @Override
    public Serializable generate(SharedSessionContractImplementor session, Object object) {
        try {
            long id = session.doReturningWork(connection -> {
                ensureSequenceRow(connection);
                return allocateId(connection);
            });
            // Return the correct type matching the entity's ID field
            if (idType == Integer.class || idType == Integer.TYPE) {
                return (int) id;
            }
            return id;
        } catch (HibernateException e) {
            throw e;
        } catch (Exception e) {
            throw new HibernateException("Failed to generate ID from " + tableName, e);
        }
    }

    /**
     * Ensure the sequence row exists in the table.
     * Uses {@code INSERT OR IGNORE} (SQLite) or equivalent.
     */
    private void ensureSequenceRow(Connection connection) throws SQLException {
        String sql = "INSERT INTO " + tableName + " (sequence_name, next_val) VALUES (?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, segmentValue);
            stmt.setLong(2, INITIAL_VALUE);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // Row already exists — this is expected after the first call.
            // SQLite throws SQLITE_CONSTRAINT on UNIQUE violation.
            // PostgreSQL throws PSQLException with SQLState 23505.
            if (!isUniqueViolation(e)) {
                throw e;
            }
        }
    }

    /**
     * Allocate the next ID using a CAS loop:
     * {@code SELECT next_val, UPDATE next_val = next_val + 1 WHERE next_val = old_value}
     * <p>
     * Returns the value BEFORE the increment (1-based, matching
     * {@code GenerationType.TABLE} semantics).
     */
    private long allocateId(Connection connection) throws SQLException {
        String selectSql = "SELECT next_val FROM " + tableName + " WHERE sequence_name = ?";
        String updateSql = "UPDATE " + tableName + " SET next_val = ? WHERE sequence_name = ? AND next_val = ?";

        while (true) {
            long currentVal;
            try (PreparedStatement stmt = connection.prepareStatement(selectSql)) {
                stmt.setString(1, segmentValue);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        // Row was deleted by another session; re-insert
                        ensureSequenceRow(connection);
                        continue;
                    }
                    currentVal = rs.getLong("next_val");
                }
            }

            long newVal = currentVal + 1;
            try (PreparedStatement stmt = connection.prepareStatement(updateSql)) {
                stmt.setLong(1, newVal);
                stmt.setString(2, segmentValue);
                stmt.setLong(3, currentVal);
                int updated = stmt.executeUpdate();
                if (updated > 0) {
                    return currentVal;
                }
            }

            // CAS failed — another thread/session updated the row. Retry.
            logger.trace("CAS retry for sequence {} value={}", segmentValue, currentVal);
        }
    }

    // ── Static helpers for toEntity() conversion ──────────────────────────

    /**
     * Convert a {@code long} domain ID to {@link Long}, returning {@code null}
     * when the value is {@code 0} (i.e. a new entity).
     * <p>
     * This is used in DAO {@code toEntity()} methods to let Hibernate's
     * {@code saveOrUpdate()} distinguish new entities ({@code null}) from
     * existing ones (non-null):
     * <pre>{@code
     *     entity.setId(DiatomIdGenerator.idOrNull(domain.id));
     * }</pre>
     */
    public static Long idOrNull(long id) {
        return id > 0 ? id : null;
    }

    /**
     * Convert an {@code int} domain ID to {@link Integer}, returning {@code null}
     * when the value is {@code 0}.
     *
     * @see #idOrNull(long)
     */
    public static Integer idOrNull(int id) {
        return id > 0 ? id : null;
    }

    private static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if (state != null) {
            // 23505 = PostgreSQL unique violation
            // 23000 = SQLite unique constraint (SQLITE_CONSTRAINT)
            return "23505".equals(state) || "23000".equals(state);
        }
        String msg = e.getMessage();
        return msg != null && (msg.contains("UNIQUE") || msg.contains("unique"));
    }
}
