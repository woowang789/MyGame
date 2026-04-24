package mygame.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

public final class JdbcAccountRepository implements AccountRepository {

    private final Database db;

    public JdbcAccountRepository(Database db) {
        this.db = db;
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        String sql = "SELECT id, username, password_hash, salt FROM accounts WHERE username = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Account(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("salt")));
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
                return new Account(keys.getLong(1), username, passwordHash, salt);
            }
        } catch (SQLException e) {
            throw new RuntimeException("create 실패: " + username, e);
        }
    }
}
