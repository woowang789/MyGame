package mygame.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mygame.game.entity.MonsterTemplate;
import mygame.game.item.DropTable;
import mygame.game.item.DropTable.Entry;

public final class JdbcMonsterTemplateRepository implements MonsterTemplateRepository {

    private static final String COLUMNS =
            "id, display_name, max_hp, attack_damage, attack_interval_ms, speed,"
            + " exp_reward, respawn_delay_ms, meso_min, meso_max, body_color";

    private final SqlRunner sql;

    public JdbcMonsterTemplateRepository(Database db) {
        this.sql = db.sql();
    }

    @Override
    public Optional<MonsterTemplate> findById(String monsterId) {
        Optional<TemplateScalars> head = sql.queryOne(
                "SELECT " + COLUMNS + " FROM monster_templates WHERE id = ?",
                ps -> ps.setString(1, monsterId),
                JdbcMonsterTemplateRepository::mapScalars);
        if (head.isEmpty()) return Optional.empty();
        DropTable drops = loadDrops(monsterId);
        return Optional.of(head.get().toTemplate(drops));
    }

    @Override
    public List<MonsterTemplate> findAll() {
        // N+1 회피: 한 번에 부모 + 모든 drops 를 두 쿼리로 적재 후 메모리에서 그룹화.
        return new ArrayList<>(loadAll().values());
    }

    @Override
    public Map<String, MonsterTemplate> loadAll() {
        // 1) 부모 행 일괄 조회
        var parents = sql.queryList(
                "SELECT " + COLUMNS + " FROM monster_templates ORDER BY id ASC",
                SqlBinder.NONE,
                JdbcMonsterTemplateRepository::mapScalars);

        // 2) 자식 drops 일괄 조회 후 그룹화
        record DropRow(String monsterId, Entry entry) {}
        var dropRows = sql.queryList(
                "SELECT monster_id, item_id, chance FROM monster_drops "
                        + "ORDER BY monster_id ASC, sort_order ASC, item_id ASC",
                SqlBinder.NONE,
                rs -> new DropRow(
                        rs.getString("monster_id"),
                        new Entry(rs.getString("item_id"), rs.getDouble("chance"))));
        Map<String, List<Entry>> dropsByMonster = new LinkedHashMap<>();
        for (var d : dropRows) {
            dropsByMonster.computeIfAbsent(d.monsterId(), k -> new ArrayList<>()).add(d.entry());
        }

        // 3) 합치기
        Map<String, MonsterTemplate> out = new LinkedHashMap<>();
        for (var p : parents) {
            DropTable dt = new DropTable(dropsByMonster.getOrDefault(p.id(), List.of()));
            out.put(p.id(), p.toTemplate(dt));
        }
        return out;
    }

    @Override
    public int upsertTemplate(MonsterTemplate t) {
        return sql.update(
                "MERGE INTO monster_templates "
                        + "(id, display_name, max_hp, attack_damage, attack_interval_ms, speed,"
                        + " exp_reward, respawn_delay_ms, meso_min, meso_max, body_color) "
                        + "KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, t.id());
                    ps.setString(2, t.displayName());
                    ps.setInt(3, t.maxHp());
                    ps.setInt(4, t.attackDamage());
                    ps.setLong(5, t.attackIntervalMs());
                    ps.setDouble(6, t.speed());
                    ps.setInt(7, t.expReward());
                    ps.setLong(8, t.respawnDelayMs());
                    ps.setInt(9, t.mesoMin());
                    ps.setInt(10, t.mesoMax());
                    ps.setInt(11, t.bodyColor());
                });
    }

    @Override
    public int replaceDrops(String monsterId, DropTable drops) {
        // 트랜잭션으로 전체 교체. 부분 적용 시 일관성이 깨지면 안 됨.
        return sql.inTransaction(tx -> {
            tx.update(
                    "DELETE FROM monster_drops WHERE monster_id = ?",
                    ps -> ps.setString(1, monsterId));
            int order = 0;
            int affected = 0;
            for (Entry e : drops.entries()) {
                final int sortOrder = order++;
                tx.update(
                        "INSERT INTO monster_drops (monster_id, item_id, chance, sort_order) "
                                + "VALUES (?, ?, ?, ?)",
                        ps -> {
                            ps.setString(1, monsterId);
                            ps.setString(2, e.itemId());
                            ps.setDouble(3, e.chance());
                            ps.setInt(4, sortOrder);
                        });
                affected++;
            }
            return affected;
        });
    }

    @Override
    public int upsertDropLine(String monsterId, String itemId, double chance, int sortOrder) {
        return sql.update(
                "MERGE INTO monster_drops (monster_id, item_id, chance, sort_order) "
                        + "KEY(monster_id, item_id) VALUES (?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, monsterId);
                    ps.setString(2, itemId);
                    ps.setDouble(3, chance);
                    ps.setInt(4, sortOrder);
                });
    }

    @Override
    public int deleteDropLine(String monsterId, String itemId) {
        return sql.update(
                "DELETE FROM monster_drops WHERE monster_id = ? AND item_id = ?",
                ps -> {
                    ps.setString(1, monsterId);
                    ps.setString(2, itemId);
                });
    }

    @Override
    public int deleteById(String monsterId) {
        // monster_drops 는 FK CASCADE — 부모 삭제만으로 충분.
        return sql.update(
                "DELETE FROM monster_templates WHERE id = ?",
                ps -> ps.setString(1, monsterId));
    }

    private DropTable loadDrops(String monsterId) {
        var entries = sql.queryList(
                "SELECT item_id, chance FROM monster_drops "
                        + "WHERE monster_id = ? ORDER BY sort_order ASC, item_id ASC",
                ps -> ps.setString(1, monsterId),
                rs -> new Entry(rs.getString("item_id"), rs.getDouble("chance")));
        return new DropTable(entries);
    }

    /** ResultSet 에서 부모 스칼라만 읽어 둔 뒤 DropTable 과 합치는 임시 캐리어. */
    private record TemplateScalars(
            String id, String displayName, int maxHp, int attackDamage, long attackIntervalMs,
            double speed, int expReward, long respawnDelayMs, int mesoMin, int mesoMax, int bodyColor) {

        MonsterTemplate toTemplate(DropTable drops) {
            return new MonsterTemplate(id, displayName, maxHp, attackDamage,
                    attackIntervalMs, speed, expReward, respawnDelayMs,
                    mesoMin, mesoMax, drops, bodyColor);
        }
    }

    private static TemplateScalars mapScalars(ResultSet rs) throws SQLException {
        return new TemplateScalars(
                rs.getString("id"), rs.getString("display_name"),
                rs.getInt("max_hp"), rs.getInt("attack_damage"),
                rs.getLong("attack_interval_ms"), rs.getDouble("speed"),
                rs.getInt("exp_reward"), rs.getLong("respawn_delay_ms"),
                rs.getInt("meso_min"), rs.getInt("meso_max"),
                rs.getInt("body_color"));
    }
}
