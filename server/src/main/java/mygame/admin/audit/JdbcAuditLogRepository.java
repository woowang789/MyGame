package mygame.admin.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import mygame.db.Database;

public final class JdbcAuditLogRepository implements AuditLogRepository {

    private final Database db;

    public JdbcAuditLogRepository(Database db) {
        this.db = db;
    }

    @Override
    public void append(Long adminId, String adminUsername, String action, String payload) {
        String sql = "INSERT INTO audit_log (admin_id, admin_username, action, payload) VALUES (?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (adminId == null) ps.setNull(1, Types.BIGINT);
            else ps.setLong(1, adminId);
            ps.setString(2, adminUsername);
            ps.setString(3, action);
            if (payload == null) ps.setNull(4, Types.CLOB);
            else ps.setString(4, payload);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("audit append 실패: " + action, e);
        }
    }

    @Override
    public List<Entry> recent(int limit) {
        String sql = "SELECT id, admin_id, admin_username, action, payload, created_at "
                + "FROM audit_log ORDER BY id DESC LIMIT ?";
        List<Entry> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long adminIdRaw = rs.getLong("admin_id");
                    Long adminId = rs.wasNull() ? null : adminIdRaw;
                    Timestamp ts = rs.getTimestamp("created_at");
                    list.add(new Entry(
                            rs.getLong("id"),
                            adminId,
                            rs.getString("admin_username"),
                            rs.getString("action"),
                            rs.getString("payload"),
                            ts == null ? null : ts.toInstant()));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("audit recent 실패", e);
        }
        return list;
    }
}
