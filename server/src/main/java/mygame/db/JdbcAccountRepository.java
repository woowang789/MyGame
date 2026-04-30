package mygame.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

public final class JdbcAccountRepository implements AccountRepository {

    private final SqlRunner sql;

    public JdbcAccountRepository(Database db) {
        this.sql = db.sql();
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        return sql.queryOne(
                "SELECT id, username, password_hash, salt, disabled FROM accounts WHERE username = ?",
                ps -> ps.setString(1, username),
                rs -> new Account(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("salt"),
                        rs.getBoolean("disabled")));
    }

    @Override
    public Account create(String username, String passwordHash, String salt) {
        long id = sql.insertReturningKey(
                "INSERT INTO accounts (username, password_hash, salt) VALUES (?, ?, ?)",
                ps -> {
                    ps.setString(1, username);
                    ps.setString(2, passwordHash);
                    ps.setString(3, salt);
                });
        return new Account(id, username, passwordHash, salt, false);
    }

    @Override
    public int setDisabled(long accountId, boolean disabled) {
        return sql.update(
                "UPDATE accounts SET disabled = ? WHERE id = ?",
                ps -> {
                    ps.setBoolean(1, disabled);
                    ps.setLong(2, accountId);
                });
    }

    @Override
    public int updatePassword(long accountId, String passwordHash, String salt) {
        // 메시지에 비밀번호 정보가 들어가지 않도록 id 만 노출 — SqlRunner 의 wrap 도 SQL 까지만 포함.
        return sql.update(
                "UPDATE accounts SET password_hash = ?, salt = ? WHERE id = ?",
                ps -> {
                    ps.setString(1, passwordHash);
                    ps.setString(2, salt);
                    ps.setLong(3, accountId);
                });
    }

    @Override
    public Optional<AccountSummary> findById(long accountId) {
        return sql.queryOne(
                "SELECT id, username, created_at, disabled FROM accounts WHERE id = ?",
                ps -> ps.setLong(1, accountId),
                JdbcAccountRepository::mapSummary);
    }

    @Override
    public List<AccountSummary> findPage(int offset, int limit) {
        // 비밀번호 해시·솔트는 SELECT 에서 제외해 백오피스 노출 표면을 줄인다.
        return sql.queryList(
                "SELECT id, username, created_at, disabled FROM accounts ORDER BY id ASC LIMIT ? OFFSET ?",
                ps -> {
                    ps.setInt(1, limit);
                    ps.setInt(2, offset);
                },
                JdbcAccountRepository::mapSummary);
    }

    @Override
    public long count() {
        return sql.queryLong("SELECT COUNT(*) FROM accounts", SqlBinder.NONE);
    }

    private static AccountSummary mapSummary(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        return new AccountSummary(
                rs.getLong("id"),
                rs.getString("username"),
                ts == null ? null : ts.toInstant(),
                rs.getBoolean("disabled"));
    }
}
