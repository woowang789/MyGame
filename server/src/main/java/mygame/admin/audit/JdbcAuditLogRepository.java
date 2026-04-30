package mygame.admin.audit;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import mygame.db.Database;
import mygame.db.SqlRunner;

public final class JdbcAuditLogRepository implements AuditLogRepository {

    private final SqlRunner sql;

    public JdbcAuditLogRepository(Database db) {
        this.sql = db.sql();
    }

    @Override
    public void append(Long adminId, String adminUsername, String action, String payload) {
        sql.update(
                "INSERT INTO audit_log (admin_id, admin_username, action, payload) VALUES (?, ?, ?, ?)",
                ps -> {
                    if (adminId == null) ps.setNull(1, Types.BIGINT);
                    else ps.setLong(1, adminId);
                    ps.setString(2, adminUsername);
                    ps.setString(3, action);
                    if (payload == null) ps.setNull(4, Types.CLOB);
                    else ps.setString(4, payload);
                });
    }

    @Override
    public List<Entry> recent(int limit) {
        return sql.queryList(
                "SELECT id, admin_id, admin_username, action, payload, created_at "
                        + "FROM audit_log ORDER BY id DESC LIMIT ?",
                ps -> ps.setInt(1, limit),
                rs -> {
                    long adminIdRaw = rs.getLong("admin_id");
                    Long adminId = rs.wasNull() ? null : adminIdRaw;
                    Timestamp ts = rs.getTimestamp("created_at");
                    return new Entry(
                            rs.getLong("id"),
                            adminId,
                            rs.getString("admin_username"),
                            rs.getString("action"),
                            rs.getString("payload"),
                            ts == null ? null : ts.toInstant());
                });
    }
}
