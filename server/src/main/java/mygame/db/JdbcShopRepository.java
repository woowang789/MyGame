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
import mygame.game.shop.ShopCatalog;
import mygame.game.shop.ShopCatalog.Entry;

/** {@link ShopRepository} 의 JDBC 구현. */
public final class JdbcShopRepository implements ShopRepository {

    private final Database db;

    public JdbcShopRepository(Database db) {
        this.db = db;
    }

    @Override
    public Optional<ShopCatalog> findById(String shopId) {
        String sql = "SELECT item_id, price, stock_per_tx FROM shop_items "
                + "WHERE shop_id = ? ORDER BY sort_order ASC, item_id ASC";
        // shops 행이 있는지 먼저 확인 — 0 행 카탈로그도 운영 시점에 가능하므로
        // 빈 리스트와 미존재를 구분한다.
        if (findSummary(shopId).isEmpty()) return Optional.empty();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Entry> entries = new ArrayList<>();
                while (rs.next()) {
                    entries.add(new Entry(
                            rs.getString("item_id"),
                            rs.getLong("price"),
                            rs.getInt("stock_per_tx")));
                }
                return Optional.of(new ShopCatalog(shopId, entries));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findById 실패: shopId=" + shopId, e);
        }
    }

    @Override
    public Optional<ShopSummary> findSummary(String shopId) {
        String sql = "SELECT s.id, s.name, COALESCE(c.cnt, 0) AS cnt FROM shops s "
                + "LEFT JOIN (SELECT shop_id, COUNT(*) AS cnt FROM shop_items GROUP BY shop_id) c "
                + "ON c.shop_id = s.id WHERE s.id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new ShopSummary(
                        rs.getString("id"), rs.getString("name"), rs.getInt("cnt")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findSummary 실패: shopId=" + shopId, e);
        }
    }

    @Override
    public List<ShopSummary> findAllSummaries() {
        String sql = "SELECT s.id, s.name, COALESCE(c.cnt, 0) AS cnt FROM shops s "
                + "LEFT JOIN (SELECT shop_id, COUNT(*) AS cnt FROM shop_items GROUP BY shop_id) c "
                + "ON c.shop_id = s.id ORDER BY s.id ASC";
        List<ShopSummary> out = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new ShopSummary(
                        rs.getString("id"), rs.getString("name"), rs.getInt("cnt")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("findAllSummaries 실패", e);
        }
        return out;
    }

    @Override
    public Map<String, ShopCatalog> loadAllCatalogs() {
        // 단일 쿼리로 전체 적재 — 부트스트랩 비용 최소. (shop_id, sort_order, item_id) 정렬로
        // 같은 shop 행들이 인접 + entries 가 정렬된 상태로 순회 가능.
        String sql = "SELECT shop_id, item_id, price, stock_per_tx FROM shop_items "
                + "ORDER BY shop_id ASC, sort_order ASC, item_id ASC";
        Map<String, List<Entry>> grouped = new LinkedHashMap<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                grouped.computeIfAbsent(rs.getString("shop_id"), k -> new ArrayList<>())
                        .add(new Entry(
                                rs.getString("item_id"),
                                rs.getLong("price"),
                                rs.getInt("stock_per_tx")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("loadAllCatalogs 실패", e);
        }
        // shops 테이블에 행이 있지만 shop_items 가 비어 있는 상점도 누락되지 않도록 한 번 더 훑음.
        for (ShopSummary s : findAllSummaries()) {
            grouped.putIfAbsent(s.id(), new ArrayList<>());
        }
        Map<String, ShopCatalog> out = new LinkedHashMap<>();
        for (var e : grouped.entrySet()) {
            out.put(e.getKey(), new ShopCatalog(e.getKey(), e.getValue()));
        }
        return out;
    }

    @Override
    public int upsertShop(String shopId, String name) {
        // H2 의 MERGE 는 키 기반 upsert — 직접 INSERT 후 충돌 시 UPDATE 하는 패턴보다 단순.
        String sql = "MERGE INTO shops (id, name) KEY(id) VALUES (?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shopId);
            ps.setString(2, name == null ? "" : name);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("upsertShop 실패: shopId=" + shopId, e);
        }
    }

    @Override
    public int upsertItem(String shopId, String itemId, long price, int stockPerTx, int sortOrder) {
        String sql = "MERGE INTO shop_items (shop_id, item_id, price, stock_per_tx, sort_order) "
                + "KEY(shop_id, item_id) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shopId);
            ps.setString(2, itemId);
            ps.setLong(3, price);
            ps.setInt(4, stockPerTx);
            ps.setInt(5, sortOrder);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(
                    "upsertItem 실패: shopId=" + shopId + " itemId=" + itemId, e);
        }
    }

    @Override
    public int deleteItem(String shopId, String itemId) {
        String sql = "DELETE FROM shop_items WHERE shop_id = ? AND item_id = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shopId);
            ps.setString(2, itemId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(
                    "deleteItem 실패: shopId=" + shopId + " itemId=" + itemId, e);
        }
    }
}
