package mygame.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
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

    private final Database db;

    public JdbcMonsterTemplateRepository(Database db) {
        this.db = db;
    }

    @Override
    public Optional<MonsterTemplate> findById(String monsterId) {
        String sql = "SELECT " + COLUMNS + " FROM monster_templates WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, monsterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                DropTable drops = loadDrops(conn, monsterId);
                return Optional.of(map(rs, drops));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findById 실패: id=" + monsterId, e);
        }
    }

    @Override
    public List<MonsterTemplate> findAll() {
        // N+1 회피: 한 번에 부모 + 모든 drops 를 두 쿼리로 적재 후 메모리에서 그룹화.
        Map<String, MonsterTemplate> all = loadAll();
        return new ArrayList<>(all.values());
    }

    @Override
    public Map<String, MonsterTemplate> loadAll() {
        // 1) 부모 행 일괄 조회
        String parentSql = "SELECT " + COLUMNS + " FROM monster_templates ORDER BY id ASC";
        Map<String, ResultSetSnapshot> parents = new LinkedHashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(parentSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                parents.put(rs.getString("id"), ResultSetSnapshot.from(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("loadAll(parents) 실패", e);
        }

        // 2) 자식 drops 일괄 조회 후 그룹화
        Map<String, List<Entry>> dropsByMonster = new LinkedHashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT monster_id, item_id, chance FROM monster_drops "
                             + "ORDER BY monster_id ASC, sort_order ASC, item_id ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                dropsByMonster
                        .computeIfAbsent(rs.getString("monster_id"), k -> new ArrayList<>())
                        .add(new Entry(rs.getString("item_id"), rs.getDouble("chance")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("loadAll(drops) 실패", e);
        }

        // 3) 합치기
        Map<String, MonsterTemplate> out = new LinkedHashMap<>();
        for (var e : parents.entrySet()) {
            DropTable dt = new DropTable(dropsByMonster.getOrDefault(e.getKey(), List.of()));
            out.put(e.getKey(), e.getValue().toTemplate(dt));
        }
        return out;
    }

    @Override
    public int upsertTemplate(MonsterTemplate t) {
        String sql = "MERGE INTO monster_templates "
                + "(id, display_name, max_hp, attack_damage, attack_interval_ms, speed,"
                + " exp_reward, respawn_delay_ms, meso_min, meso_max, body_color) "
                + "KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
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
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("upsertTemplate 실패: id=" + t.id(), e);
        }
    }

    @Override
    public int replaceDrops(String monsterId, DropTable drops) {
        // 트랜잭션으로 전체 교체. 부분 적용 시 일관성이 깨지면 안 됨.
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM monster_drops WHERE monster_id = ?")) {
                    del.setString(1, monsterId);
                    del.executeUpdate();
                }
                int order = 0;
                int affected = 0;
                String insSql = "INSERT INTO monster_drops "
                        + "(monster_id, item_id, chance, sort_order) VALUES (?, ?, ?, ?)";
                try (PreparedStatement ins = conn.prepareStatement(insSql)) {
                    for (Entry e : drops.entries()) {
                        ins.setString(1, monsterId);
                        ins.setString(2, e.itemId());
                        ins.setDouble(3, e.chance());
                        ins.setInt(4, order++);
                        ins.executeUpdate();
                        affected++;
                    }
                }
                conn.commit();
                return affected;
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            throw new RuntimeException("replaceDrops 실패: id=" + monsterId, e);
        }
    }

    @Override
    public int upsertDropLine(String monsterId, String itemId, double chance, int sortOrder) {
        String sql = "MERGE INTO monster_drops (monster_id, item_id, chance, sort_order) "
                + "KEY(monster_id, item_id) VALUES (?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, monsterId);
            ps.setString(2, itemId);
            ps.setDouble(3, chance);
            ps.setInt(4, sortOrder);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(
                    "upsertDropLine 실패: " + monsterId + "/" + itemId, e);
        }
    }

    @Override
    public int deleteDropLine(String monsterId, String itemId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM monster_drops WHERE monster_id = ? AND item_id = ?")) {
            ps.setString(1, monsterId);
            ps.setString(2, itemId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(
                    "deleteDropLine 실패: " + monsterId + "/" + itemId, e);
        }
    }

    @Override
    public int deleteById(String monsterId) {
        // monster_drops 는 FK CASCADE — 부모 삭제만으로 충분.
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM monster_templates WHERE id = ?")) {
            ps.setString(1, monsterId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("deleteById 실패: id=" + monsterId, e);
        }
    }

    private DropTable loadDrops(Connection conn, String monsterId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT item_id, chance FROM monster_drops "
                        + "WHERE monster_id = ? ORDER BY sort_order ASC, item_id ASC")) {
            ps.setString(1, monsterId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Entry> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new Entry(rs.getString("item_id"), rs.getDouble("chance")));
                }
                return new DropTable(list);
            }
        }
    }

    private static MonsterTemplate map(ResultSet rs, DropTable drops) throws SQLException {
        return new MonsterTemplate(
                rs.getString("id"),
                rs.getString("display_name"),
                rs.getInt("max_hp"),
                rs.getInt("attack_damage"),
                rs.getLong("attack_interval_ms"),
                rs.getDouble("speed"),
                rs.getInt("exp_reward"),
                rs.getLong("respawn_delay_ms"),
                rs.getInt("meso_min"),
                rs.getInt("meso_max"),
                drops,
                rs.getInt("body_color"));
    }

    /** loadAll 에서 ResultSet 을 닫기 전에 값만 추출해 두는 임시 스냅샷. */
    private record ResultSetSnapshot(
            String id, String displayName, int maxHp, int attackDamage, long attackIntervalMs,
            double speed, int expReward, long respawnDelayMs, int mesoMin, int mesoMax, int bodyColor) {

        static ResultSetSnapshot from(ResultSet rs) throws SQLException {
            return new ResultSetSnapshot(
                    rs.getString("id"), rs.getString("display_name"),
                    rs.getInt("max_hp"), rs.getInt("attack_damage"),
                    rs.getLong("attack_interval_ms"), rs.getDouble("speed"),
                    rs.getInt("exp_reward"), rs.getLong("respawn_delay_ms"),
                    rs.getInt("meso_min"), rs.getInt("meso_max"),
                    rs.getInt("body_color"));
        }

        MonsterTemplate toTemplate(DropTable drops) {
            return new MonsterTemplate(id, displayName, maxHp, attackDamage,
                    attackIntervalMs, speed, expReward, respawnDelayMs,
                    mesoMin, mesoMax, drops, bodyColor);
        }
    }
}
