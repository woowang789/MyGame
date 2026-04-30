package mygame.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mygame.game.shop.ShopCatalog;
import mygame.game.shop.ShopCatalog.Entry;

/** {@link ShopRepository} 의 JDBC 구현. */
public final class JdbcShopRepository implements ShopRepository {

    private final SqlRunner sql;

    public JdbcShopRepository(Database db) {
        this.sql = db.sql();
    }

    @Override
    public Optional<ShopCatalog> findById(String shopId) {
        // shops 행이 있는지 먼저 확인 — 0 행 카탈로그도 운영 시점에 가능하므로
        // 빈 리스트와 미존재를 구분한다.
        if (findSummary(shopId).isEmpty()) return Optional.empty();
        List<Entry> entries = sql.queryList(
                "SELECT item_id, price, stock_per_tx FROM shop_items "
                        + "WHERE shop_id = ? ORDER BY sort_order ASC, item_id ASC",
                ps -> ps.setString(1, shopId),
                rs -> new Entry(
                        rs.getString("item_id"),
                        rs.getLong("price"),
                        rs.getInt("stock_per_tx")));
        return Optional.of(new ShopCatalog(shopId, entries));
    }

    @Override
    public Optional<ShopSummary> findSummary(String shopId) {
        return sql.queryOne(
                "SELECT s.id, s.name, COALESCE(c.cnt, 0) AS cnt FROM shops s "
                        + "LEFT JOIN (SELECT shop_id, COUNT(*) AS cnt FROM shop_items GROUP BY shop_id) c "
                        + "ON c.shop_id = s.id WHERE s.id = ?",
                ps -> ps.setString(1, shopId),
                rs -> new ShopSummary(
                        rs.getString("id"), rs.getString("name"), rs.getInt("cnt")));
    }

    @Override
    public List<ShopSummary> findAllSummaries() {
        return sql.queryList(
                "SELECT s.id, s.name, COALESCE(c.cnt, 0) AS cnt FROM shops s "
                        + "LEFT JOIN (SELECT shop_id, COUNT(*) AS cnt FROM shop_items GROUP BY shop_id) c "
                        + "ON c.shop_id = s.id ORDER BY s.id ASC",
                SqlBinder.NONE,
                rs -> new ShopSummary(
                        rs.getString("id"), rs.getString("name"), rs.getInt("cnt")));
    }

    @Override
    public Map<String, ShopCatalog> loadAllCatalogs() {
        // 단일 쿼리로 전체 적재 — 부트스트랩 비용 최소. (shop_id, sort_order, item_id) 정렬로
        // 같은 shop 행들이 인접 + entries 가 정렬된 상태로 순회 가능.
        record Row(String shopId, Entry entry) {}
        var rows = sql.queryList(
                "SELECT shop_id, item_id, price, stock_per_tx FROM shop_items "
                        + "ORDER BY shop_id ASC, sort_order ASC, item_id ASC",
                SqlBinder.NONE,
                rs -> new Row(rs.getString("shop_id"), new Entry(
                        rs.getString("item_id"),
                        rs.getLong("price"),
                        rs.getInt("stock_per_tx"))));
        Map<String, List<Entry>> grouped = new LinkedHashMap<>();
        for (var r : rows) {
            grouped.computeIfAbsent(r.shopId(), k -> new ArrayList<>()).add(r.entry());
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
        return sql.update(
                "MERGE INTO shops (id, name) KEY(id) VALUES (?, ?)",
                ps -> {
                    ps.setString(1, shopId);
                    ps.setString(2, name == null ? "" : name);
                });
    }

    @Override
    public int upsertItem(String shopId, String itemId, long price, int stockPerTx, int sortOrder) {
        return sql.update(
                "MERGE INTO shop_items (shop_id, item_id, price, stock_per_tx, sort_order) "
                        + "KEY(shop_id, item_id) VALUES (?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, shopId);
                    ps.setString(2, itemId);
                    ps.setLong(3, price);
                    ps.setInt(4, stockPerTx);
                    ps.setInt(5, sortOrder);
                });
    }

    @Override
    public int deleteItem(String shopId, String itemId) {
        return sql.update(
                "DELETE FROM shop_items WHERE shop_id = ? AND item_id = ?",
                ps -> {
                    ps.setString(1, shopId);
                    ps.setString(2, itemId);
                });
    }
}
