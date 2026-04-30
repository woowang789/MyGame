package mygame.admin.auth;

import java.util.Optional;
import mygame.db.Database;
import mygame.db.SqlBinder;
import mygame.db.SqlRunner;

/**
 * {@link AdminAccountRepository} 의 JDBC 구현. 게임 측 {@code JdbcAccountRepository}
 * 와 같은 패턴을 따른다.
 */
public final class JdbcAdminAccountRepository implements AdminAccountRepository {

    private final SqlRunner sql;

    public JdbcAdminAccountRepository(Database db) {
        this.sql = db.sql();
    }

    @Override
    public Optional<AdminAccount> findByUsername(String username) {
        return sql.queryOne(
                "SELECT id, username, password_hash, salt, role FROM admin_accounts WHERE username = ?",
                ps -> ps.setString(1, username),
                rs -> new AdminAccount(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("salt"),
                        rs.getString("role")));
    }

    @Override
    public AdminAccount create(String username, String passwordHash, String salt, String role) {
        long id = sql.insertReturningKey(
                "INSERT INTO admin_accounts (username, password_hash, salt, role) VALUES (?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, username);
                    ps.setString(2, passwordHash);
                    ps.setString(3, salt);
                    ps.setString(4, role);
                });
        return new AdminAccount(id, username, passwordHash, salt, role);
    }

    @Override
    public long count() {
        return sql.queryLong("SELECT COUNT(*) FROM admin_accounts", SqlBinder.NONE);
    }
}
