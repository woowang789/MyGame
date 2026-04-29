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
import mygame.game.item.EquipSlot;
import mygame.game.item.ItemTemplate;
import mygame.game.item.ItemTemplate.ItemType;
import mygame.game.item.ItemTemplate.UseEffect;
import mygame.game.stat.Stats;

/** {@link ItemTemplateRepository} 의 JDBC 구현. */
public final class JdbcItemTemplateRepository implements ItemTemplateRepository {

    private static final String COLUMNS =
            "id, name, color, type, slot, bonus_max_hp, bonus_max_mp, bonus_attack, bonus_speed,"
            + " use_heal, use_mana_heal, sell_price";

    private final Database db;

    public JdbcItemTemplateRepository(Database db) {
        this.db = db;
    }

    @Override
    public Optional<ItemTemplate> findById(String itemId) {
        String sql = "SELECT " + COLUMNS + " FROM item_templates WHERE id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findById 실패: id=" + itemId, e);
        }
    }

    @Override
    public List<ItemTemplate> findAll() {
        List<ItemTemplate> out = new ArrayList<>();
        String sql = "SELECT " + COLUMNS + " FROM item_templates ORDER BY id ASC";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) {
            throw new RuntimeException("findAll 실패", e);
        }
        return out;
    }

    @Override
    public Map<String, ItemTemplate> loadAll() {
        Map<String, ItemTemplate> out = new LinkedHashMap<>();
        for (ItemTemplate t : findAll()) out.put(t.id(), t);
        return out;
    }

    @Override
    public int upsert(ItemTemplate t) {
        String sql = "MERGE INTO item_templates "
                + "(id, name, color, type, slot,"
                + " bonus_max_hp, bonus_max_mp, bonus_attack, bonus_speed,"
                + " use_heal, use_mana_heal, sell_price) "
                + "KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.id());
            ps.setString(2, t.name());
            ps.setInt(3, t.color());
            ps.setString(4, t.type().name());
            ps.setString(5, t.slot() == null ? null : t.slot().name());
            // null 인 bonus/use 는 0 으로 평탄화 — DB 컬럼이 NOT NULL DEFAULT 0.
            Stats b = t.bonus();
            ps.setInt(6, b == null ? 0 : b.maxHp());
            ps.setInt(7, b == null ? 0 : b.maxMp());
            ps.setInt(8, b == null ? 0 : b.attack());
            ps.setInt(9, b == null ? 0 : b.speed());
            UseEffect u = t.use();
            ps.setInt(10, u == null ? 0 : u.heal());
            ps.setInt(11, u == null ? 0 : u.manaHeal());
            ps.setLong(12, t.sellPrice());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("upsert 실패: id=" + t.id(), e);
        }
    }

    @Override
    public int deleteById(String itemId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM item_templates WHERE id = ?")) {
            ps.setString(1, itemId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("deleteById 실패: id=" + itemId, e);
        }
    }

    @Override
    public int countShopReferences(String itemId) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM shop_items WHERE item_id = ?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("countShopReferences 실패: id=" + itemId, e);
        }
    }

    /**
     * ResultSet → ItemTemplate. type 에 따라 convenience ctor 를 골라 record 의
     * invariants 를 도메인 단에서 한 번 더 검증.
     */
    private static ItemTemplate map(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("name");
        int color = rs.getInt("color");
        ItemType type = ItemType.valueOf(rs.getString("type"));
        long sellPrice = rs.getLong("sell_price");
        return switch (type) {
            case EQUIPMENT -> {
                String slotName = rs.getString("slot");
                if (slotName == null) {
                    throw new IllegalStateException("EQUIPMENT 인데 slot 이 NULL: id=" + id);
                }
                Stats bonus = new Stats(
                        rs.getInt("bonus_max_hp"),
                        rs.getInt("bonus_max_mp"),
                        rs.getInt("bonus_attack"),
                        rs.getInt("bonus_speed"));
                yield new ItemTemplate(id, name, color, type,
                        EquipSlot.valueOf(slotName), bonus, sellPrice);
            }
            case CONSUMABLE -> {
                UseEffect use = new UseEffect(rs.getInt("use_heal"), rs.getInt("use_mana_heal"));
                yield new ItemTemplate(id, name, color, type, use, sellPrice);
            }
            case ETC -> new ItemTemplate(id, name, color, type, sellPrice);
        };
    }
}
