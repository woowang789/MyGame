package mygame.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JdbcAccountRepository implements AccountRepository {

    private final Database db;

    public JdbcAccountRepository(Database db) {
        this.db = db;
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        String sql = "SELECT id, username, password_hash, salt, disabled FROM accounts WHERE username = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Account(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("salt"),
                        rs.getBoolean("disabled")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findByUsername 실패: " + username, e);
        }
    }

    @Override
    public Account create(String username, String passwordHash, String salt) {
        String sql = "INSERT INTO accounts (username, password_hash, salt) VALUES (?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, salt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("INSERT 후 id 조회 실패");
                return new Account(keys.getLong(1), username, passwordHash, salt, false);
            }
        } catch (SQLException e) {
            throw new RuntimeException("create 실패: " + username, e);
        }
    }

    @Override
    public int setDisabled(long accountId, boolean disabled) {
        String sql = "UPDATE accounts SET disabled = ? WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, disabled);
            ps.setLong(2, accountId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("setDisabled 실패: id=" + accountId, e);
        }
    }

    @Override
    public List<AccountSummary> findPage(int offset, int limit) {
        // 비밀번호 해시·솔트는 SELECT 에서 제외해 백오피스 노출 표면을 줄인다.
        String sql = "SELECT id, username, created_at, disabled FROM accounts ORDER BY id ASC LIMIT ? OFFSET ?";
        List<AccountSummary> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("created_at");
                    list.add(new AccountSummary(
                            rs.getLong("id"),
                            rs.getString("username"),
                            ts == null ? null : ts.toInstant(),
                            rs.getBoolean("disabled")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("findPage 실패: offset=" + offset + " limit=" + limit, e);
        }
        return list;
    }

    @Override
    public long count() {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM accounts");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new RuntimeException("count 실패", e);
        }
    }
}
