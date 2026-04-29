package mygame.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mygame.db.ShopRepository;
import mygame.game.shop.ShopCatalog;
import mygame.game.shop.ShopCatalog.Entry;
import mygame.game.shop.ShopRegistry;

/**
 * 테스트 가짜 헬퍼 모음. AdminFacade ctor 가 늘어날 때마다 모든 테스트의 가짜 구현을
 * 일일이 갱신하지 않도록 공통 가짜를 한 곳에 둔다.
 */
public final class TestRepos {

    private TestRepos() {}

    /**
     * ShopRegistry 를 기존 코드 상수와 동일한 카탈로그로 1회 부트스트랩.
     * 게임 측 ShopServiceTest 등 ShopRegistry.find() 에 의존하는 테스트가 호출.
     * 멀티 테스트에서 안전하도록 멱등 — 동일 데이터로 재호출.
     */
    public static void bootstrapDefaultShops() {
        Map<String, ShopCatalog> seed = new LinkedHashMap<>();
        seed.put("henesys_general", new ShopCatalog("henesys_general", List.of(
                new Entry("red_potion", 50, 50),
                new Entry("blue_potion", 80, 50),
                new Entry("wooden_sword", 1500, 1),
                new Entry("leather_cap", 800, 1)
        )));
        ShopRegistry.bootstrap(new InMemoryShopRepo(seed));
    }

    /** 임의 카탈로그를 인메모리에 깔고 ShopRegistry 를 부트스트랩 — 테스트 격리용. */
    public static void bootstrapShops(Map<String, ShopCatalog> catalogs) {
        ShopRegistry.bootstrap(new InMemoryShopRepo(new LinkedHashMap<>(catalogs)));
    }

    /** 모든 메서드가 빈 결과/0 을 반환하는 ShopRepository. */
    public static ShopRepository emptyShopRepo() {
        return new ShopRepository() {
            @Override public Optional<ShopCatalog> findById(String shopId) { return Optional.empty(); }
            @Override public Optional<ShopSummary> findSummary(String shopId) { return Optional.empty(); }
            @Override public List<ShopSummary> findAllSummaries() { return List.of(); }
            @Override public Map<String, ShopCatalog> loadAllCatalogs() { return Map.of(); }
            @Override public int upsertShop(String shopId, String name) { return 0; }
            @Override public int upsertItem(String shopId, String itemId,
                                            long price, int stockPerTx, int sortOrder) { return 0; }
            @Override public int deleteItem(String shopId, String itemId) { return 0; }
        };
    }

    /** 인메모리 ShopRepository — bootstrapShops 가 사용. */
    private static final class InMemoryShopRepo implements ShopRepository {
        private final Map<String, ShopCatalog> data;
        InMemoryShopRepo(Map<String, ShopCatalog> data) { this.data = data; }

        @Override public Optional<ShopCatalog> findById(String shopId) {
            return Optional.ofNullable(data.get(shopId));
        }
        @Override public Optional<ShopSummary> findSummary(String shopId) {
            ShopCatalog c = data.get(shopId);
            return c == null ? Optional.empty()
                    : Optional.of(new ShopSummary(shopId, "test:" + shopId, c.items().size()));
        }
        @Override public List<ShopSummary> findAllSummaries() {
            return data.values().stream()
                    .map(c -> new ShopSummary(c.shopId(), "test:" + c.shopId(), c.items().size()))
                    .toList();
        }
        @Override public Map<String, ShopCatalog> loadAllCatalogs() { return Map.copyOf(data); }
        @Override public int upsertShop(String shopId, String name) { return 1; }
        @Override public int upsertItem(String shopId, String itemId,
                                        long price, int stockPerTx, int sortOrder) {
            // 인메모리 갱신 — admin 명령 reload 통합 테스트용.
            ShopCatalog c = data.get(shopId);
            if (c == null) {
                data.put(shopId, new ShopCatalog(shopId, List.of(
                        new Entry(itemId, price, stockPerTx))));
                return 1;
            }
            java.util.List<Entry> next = new java.util.ArrayList<>(c.items());
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i).itemId().equals(itemId)) {
                    next.set(i, new Entry(itemId, price, stockPerTx));
                    data.put(shopId, new ShopCatalog(shopId, next));
                    return 1;
                }
            }
            next.add(new Entry(itemId, price, stockPerTx));
            data.put(shopId, new ShopCatalog(shopId, next));
            return 1;
        }
        @Override public int deleteItem(String shopId, String itemId) {
            ShopCatalog c = data.get(shopId);
            if (c == null) return 0;
            java.util.List<Entry> next = new java.util.ArrayList<>(c.items());
            boolean removed = next.removeIf(e -> e.itemId().equals(itemId));
            if (removed) data.put(shopId, new ShopCatalog(shopId, next));
            return removed ? 1 : 0;
        }
    }
}
