package mygame.admin.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import mygame.db.Database;

/**
 * {@link AdminAccountRepository} 의 JDBC 구현. 게임 측 {@code JdbcAccountRepository}
 * 와 같은 패턴을 따른다.
 */
public final class JdbcAdminAccountRepository implements AdminAccountRepository {

    private final Database db;

    public JdbcAdminAccountRepository(Database db) {
        this.db = db;
    }

    @Override
    public Optional<AdminAccount> findByUsername(String username) {
        String sql = "SELECT id, username, password_hash, salt, role FROM admin_accounts WHERE username = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new AdminAccount(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("salt"),
                        rs.getString("role")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("admin findByUsername 실패: " + username, e);
        }
    }

    @Override
    public AdminAccount create(String username, String passwordHash, String salt, String role) {
        String sql = "INSERT INTO admin_accounts (username, password_hash, salt, role) VALUES (?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, salt);
            ps.setString(4, role);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("INSERT 후 id 조회 실패");
                return new AdminAccount(keys.getLong(1), username, passwordHash, salt, role);
            }
        } catch (SQLException e) {
            throw new RuntimeException("admin create 실패: " + username, e);
        }
    }

    @Override
    public long count() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM admin_accounts");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new RuntimeException("admin count 실패", e);
        }
    }
}
