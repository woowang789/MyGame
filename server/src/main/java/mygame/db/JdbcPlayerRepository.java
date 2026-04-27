package mygame.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link PlayerRepository} JDBC 구현.
 *
 * <p>모든 쿼리는 {@link PreparedStatement} 로 파라미터 바인딩하여 SQL 주입을
 * 차단한다. 저장은 "삭제 후 삽입"이 아닌 UPDATE + DELETE/INSERT 트랜잭션으로
 * 부분 갱신 시 참조 무결성 문제를 피한다.
 */
public final class JdbcPlayerRepository implements PlayerRepository {

    private final Database db;

    public JdbcPlayerRepository(Database db) {
        this.db = db;
    }

    @Override
    public Optional<PlayerData> findByName(String name) {
        String sql = "SELECT id, name, level, exp, meso, hp, mp FROM players WHERE name = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            return loadOne(conn, ps);
        } catch (SQLException e) {
            throw new RuntimeException("findByName 실패: " + name, e);
        }
    }

    @Override
    public Optional<PlayerData> findByAccountId(long accountId) {
        String sql = "SELECT id, name, level, exp, meso, hp, mp FROM players WHERE account_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            return loadOne(conn, ps);
        } catch (SQLException e) {
            throw new RuntimeException("findByAccountId 실패: " + accountId, e);
        }
    }

    private Optional<PlayerData> loadOne(Connection conn, PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            long id = rs.getLong("id");
            return Optional.of(new PlayerData(
                    id, rs.getString("name"),
                    rs.getInt("level"), rs.getInt("exp"),
                    rs.getLong("meso"),
                    rs.getInt("hp"), rs.getInt("mp"),
                    loadItems(conn, id),
                    loadEquipment(conn, id)));
        }
    }

    @Override
    public PlayerData create(String name, long accountId) {
        // hp/mp 는 DEFAULT -1 (sentinel) 로 들어가 첫 로드 시 풀피로 채워진다.
        String sql = "INSERT INTO players (name, level, exp, account_id) VALUES (?, 1, 0, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setLong(2, accountId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("INSERT 후 id 조회 실패");
                long id = keys.getLong(1);
                return new PlayerData(id, name, 1, 0, 0L,
                        HP_MP_RESTORE_FULL, HP_MP_RESTORE_FULL,
                        Map.of(), Map.of());
            }
        } catch (SQLException e) {
            throw new RuntimeException("create 실패: " + name, e);
        }
    }

    @Override
    public void save(long id, int level, int exp, long meso, int hp, int mp,
                     Map<String, Integer> items, Map<String, String> equipment) {
        // 음수는 sentinel 로 보존(다음 로드 시 풀피 복원). 0 이상은 그대로 저장.
        int hpToStore = hp < 0 ? HP_MP_RESTORE_FULL : hp;
        int mpToStore = mp < 0 ? HP_MP_RESTORE_FULL : mp;
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET level=?, exp=?, meso=?, hp=?, mp=?, updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
                    ps.setInt(1, level);
                    ps.setInt(2, exp);
                    ps.setLong(3, Math.max(0, meso));
                    ps.setInt(4, hpToStore);
                    ps.setInt(5, mpToStore);
                    ps.setLong(6, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM player_items WHERE player_id=?")) {
                    del.setLong(1, id);
                    del.executeUpdate();
                }
                if (!items.isEmpty()) {
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO player_items (player_id, item_id, qty) VALUES (?, ?, ?)")) {
                        for (Map.Entry<String, Integer> e : items.entrySet()) {
                            ins.setLong(1, id);
                            ins.setString(2, e.getKey());
                            ins.setInt(3, e.getValue());
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                }
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM player_equipment WHERE player_id=?")) {
                    del.setLong(1, id);
                    del.executeUpdate();
                }
                if (!equipment.isEmpty()) {
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO player_equipment (player_id, slot, item_id) VALUES (?, ?, ?)")) {
                        for (Map.Entry<String, String> e : equipment.entrySet()) {
                            ins.setLong(1, id);
                            ins.setString(2, e.getKey());
                            ins.setString(3, e.getValue());
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("save 실패: id=" + id, e);
        }
    }

    private Map<String, Integer> loadItems(Connection conn, long playerId) throws SQLException {
        Map<String, Integer> items = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT item_id, qty FROM player_items WHERE player_id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.put(rs.getString("item_id"), rs.getInt("qty"));
                }
            }
        }
        return items;
    }

    private Map<String, String> loadEquipment(Connection conn, long playerId) throws SQLException {
        Map<String, String> eq = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT slot, item_id FROM player_equipment WHERE player_id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    eq.put(rs.getString("slot"), rs.getString("item_id"));
                }
            }
        }
        return eq;
    }
}
