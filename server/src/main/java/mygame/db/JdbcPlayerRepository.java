package mygame.db;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link PlayerRepository} JDBC 구현.
 *
 * <p>모든 쿼리는 파라미터 바인딩 ({@link SqlBinder}) 으로 SQL 주입을 차단한다.
 * 저장은 UPDATE + (DELETE/INSERT) 트랜잭션으로 부분 갱신 시 참조 무결성을 지킨다.
 */
public final class JdbcPlayerRepository implements PlayerRepository {

    private final SqlRunner sql;

    public JdbcPlayerRepository(Database db) {
        this.sql = db.sql();
    }

    @Override
    public Optional<PlayerData> findByName(String name) {
        return loadByCondition("name = ?", ps -> ps.setString(1, name));
    }

    @Override
    public Optional<PlayerData> findByAccountId(long accountId) {
        return loadByCondition("account_id = ?", ps -> ps.setLong(1, accountId));
    }

    /** 부모 행에서 자식 컬렉션을 제외한 스칼라 필드만 담는 임시 캐리어. */
    private record Head(long id, String name, int level, int exp, long meso, int hp, int mp) {}

    private Optional<PlayerData> loadByCondition(String where, SqlBinder binder) {
        // 부모 행 + 자식 컬렉션을 한 트랜잭션으로 적재해 동일 connection 을 재사용.
        return sql.inTransaction(tx -> {
            Optional<Head> head = tx.queryOne(
                    "SELECT id, name, level, exp, meso, hp, mp FROM players WHERE " + where,
                    binder,
                    rs -> new Head(
                            rs.getLong("id"), rs.getString("name"),
                            rs.getInt("level"), rs.getInt("exp"),
                            rs.getLong("meso"),
                            rs.getInt("hp"), rs.getInt("mp")));
            if (head.isEmpty()) return Optional.<PlayerData>empty();
            Head h = head.get();
            return Optional.of(new PlayerData(
                    h.id(), h.name(), h.level(), h.exp(), h.meso(), h.hp(), h.mp(),
                    loadItems(tx, h.id()), loadEquipment(tx, h.id())));
        });
    }

    @Override
    public PlayerData create(String name, long accountId) {
        // hp/mp 는 DEFAULT -1 (sentinel) 로 들어가 첫 로드 시 풀피로 채워진다.
        long id = sql.insertReturningKey(
                "INSERT INTO players (name, level, exp, account_id) VALUES (?, 1, 0, ?)",
                ps -> {
                    ps.setString(1, name);
                    ps.setLong(2, accountId);
                });
        return new PlayerData(id, name, 1, 0, 0L,
                HP_MP_RESTORE_FULL, HP_MP_RESTORE_FULL,
                Map.of(), Map.of());
    }

    @Override
    public void save(long id, int level, int exp, long meso, int hp, int mp,
                     Map<String, Integer> items, Map<String, String> equipment) {
        // 음수는 sentinel 로 보존(다음 로드 시 풀피 복원). 0 이상은 그대로 저장.
        int hpToStore = hp < 0 ? HP_MP_RESTORE_FULL : hp;
        int mpToStore = mp < 0 ? HP_MP_RESTORE_FULL : mp;
        sql.inTransaction(tx -> {
            tx.update(
                    "UPDATE players SET level=?, exp=?, meso=?, hp=?, mp=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    ps -> {
                        ps.setInt(1, level);
                        ps.setInt(2, exp);
                        ps.setLong(3, Math.max(0, meso));
                        ps.setInt(4, hpToStore);
                        ps.setInt(5, mpToStore);
                        ps.setLong(6, id);
                    });
            tx.update("DELETE FROM player_items WHERE player_id=?", ps -> ps.setLong(1, id));
            for (var e : items.entrySet()) {
                tx.update(
                        "INSERT INTO player_items (player_id, item_id, qty) VALUES (?, ?, ?)",
                        ps -> {
                            ps.setLong(1, id);
                            ps.setString(2, e.getKey());
                            ps.setInt(3, e.getValue());
                        });
            }
            tx.update("DELETE FROM player_equipment WHERE player_id=?", ps -> ps.setLong(1, id));
            for (var e : equipment.entrySet()) {
                tx.update(
                        "INSERT INTO player_equipment (player_id, slot, item_id) VALUES (?, ?, ?)",
                        ps -> {
                            ps.setLong(1, id);
                            ps.setString(2, e.getKey());
                            ps.setString(3, e.getValue());
                        });
            }
            return null;
        });
    }

    /** (key, value) 한 쌍을 담는 임시 캐리어 — RowMapper 가 부수효과 없이 동작하도록. */
    private record Pair<K, V>(K key, V value) {}

    private static Map<String, Integer> loadItems(SqlRunner.TxOps tx, long playerId)
            throws SQLException {
        var rows = tx.queryList(
                "SELECT item_id, qty FROM player_items WHERE player_id = ?",
                ps -> ps.setLong(1, playerId),
                rs -> new Pair<>(rs.getString("item_id"), rs.getInt("qty")));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var p : rows) out.put(p.key(), p.value());
        return out;
    }

    private static Map<String, String> loadEquipment(SqlRunner.TxOps tx, long playerId)
            throws SQLException {
        var rows = tx.queryList(
                "SELECT slot, item_id FROM player_equipment WHERE player_id = ?",
                ps -> ps.setLong(1, playerId),
                rs -> new Pair<>(rs.getString("slot"), rs.getString("item_id")));
        Map<String, String> out = new LinkedHashMap<>();
        for (var p : rows) out.put(p.key(), p.value());
        return out;
    }
}
