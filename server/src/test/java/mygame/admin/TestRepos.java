package mygame.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mygame.db.ItemTemplateRepository;
import mygame.db.MonsterTemplateRepository;
import mygame.db.ShopRepository;
import mygame.game.entity.MonsterRegistry;
import mygame.game.entity.MonsterTemplate;
import mygame.game.item.DropTable;
import mygame.game.item.EquipSlot;
import mygame.game.item.ItemRegistry;
import mygame.game.item.ItemTemplate;
import mygame.game.item.ItemTemplate.ItemType;
import mygame.game.item.ItemTemplate.UseEffect;
import mygame.game.shop.ShopCatalog;
import mygame.game.shop.ShopCatalog.Entry;
import mygame.game.shop.ShopRegistry;
import mygame.game.stat.Stats;

/**
 * 테스트 가짜 헬퍼 모음. AdminFacade ctor 가 늘어날 때마다 모든 테스트의 가짜 구현을
 * 일일이 갱신하지 않도록 공통 가짜를 한 곳에 둔다.
 */
public final class TestRepos {

    private TestRepos() {}

    /**
     * ItemRegistry 를 기존 코드 상수와 동일한 템플릿으로 1회 부트스트랩.
     * 게임 측 테스트에서 ItemRegistry.get/.isEquipment 를 호출하기 전에 부른다.
     */
    public static void bootstrapDefaultItems() {
        Map<String, ItemTemplate> seed = new LinkedHashMap<>();
        put(seed, new ItemTemplate("red_potion", "빨간 포션", 0xe74c3c,
                ItemType.CONSUMABLE, new UseEffect(30, 0), 25L));
        put(seed, new ItemTemplate("blue_potion", "파란 포션", 0x3498db,
                ItemType.CONSUMABLE, new UseEffect(0, 30), 40L));
        put(seed, new ItemTemplate("snail_shell", "달팽이 껍질", 0xb36836,
                ItemType.ETC, 5L));
        put(seed, new ItemTemplate("wooden_sword", "나무 검", 0x8b5a2b,
                ItemType.EQUIPMENT, EquipSlot.WEAPON, new Stats(0, 0, 10, 0), 750L));
        put(seed, new ItemTemplate("iron_sword", "철 검", 0xbfc7d5,
                ItemType.EQUIPMENT, EquipSlot.WEAPON, new Stats(0, 0, 25, 0), 1000L));
        put(seed, new ItemTemplate("leather_cap", "가죽 모자", 0x6a4e2a,
                ItemType.EQUIPMENT, EquipSlot.HAT, new Stats(15, 5, 0, 0), 400L));
        put(seed, new ItemTemplate("cloth_armor", "천 갑옷", 0xcfa16a,
                ItemType.EQUIPMENT, EquipSlot.ARMOR, new Stats(25, 0, 0, 0), 800L));
        put(seed, new ItemTemplate("work_gloves", "작업 장갑", 0x7d5b3a,
                ItemType.EQUIPMENT, EquipSlot.GLOVES, new Stats(0, 0, 4, 0), 200L));
        put(seed, new ItemTemplate("running_shoes", "달리기 신발", 0x4caf50,
                ItemType.EQUIPMENT, EquipSlot.SHOES, new Stats(0, 0, 0, 30), 200L));
        ItemRegistry.bootstrap(new InMemoryItemRepo(seed));
    }

    private static void put(Map<String, ItemTemplate> m, ItemTemplate t) {
        m.put(t.id(), t);
    }

    /**
     * MonsterRegistry 를 기본 시드로 부트스트랩. drop table 의 itemId 검증 때문에
     * ItemRegistry 가 먼저 부트스트랩돼야 함 — 본 메서드도 자동 호출.
     */
    public static void bootstrapDefaultMonsters() {
        bootstrapDefaultItems(); // drop table 검증 의존성
        Map<String, MonsterTemplate> seed = new LinkedHashMap<>();
        seed.put("snail", new MonsterTemplate("snail", "달팽이",
                50, 10, 1500, 60, 15, 5000, 5, 20,
                DropTable.of(
                        new DropTable.Entry("red_potion", 0.50),
                        new DropTable.Entry("snail_shell", 0.40)),
                0x7cd36a));
        MonsterRegistry.bootstrap(new InMemoryMonsterRepo(seed));
    }

    /**
     * ShopRegistry 를 기존 코드 상수와 동일한 카탈로그로 1회 부트스트랩.
     * 게임 측 ShopServiceTest 등 ShopRegistry.find() 에 의존하는 테스트가 호출.
     * ShopRegistry.validateAll 이 ItemRegistry.get 을 호출하므로 아이템도 같이 부트스트랩.
     */
    public static void bootstrapDefaultShopsAndItems() { bootstrapDefaultShops(); }

    public static void bootstrapDefaultShops() {
        // 무결성 검증 chain: shops → ItemRegistry.get. 아이템 캐시가 비어 있으면 실패.
        bootstrapDefaultItems();
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

    /** 모든 메서드가 빈 결과/0 을 반환하는 ItemTemplateRepository. */
    public static ItemTemplateRepository emptyItemRepo() {
        return new ItemTemplateRepository() {
            @Override public Optional<ItemTemplate> findById(String itemId) { return Optional.empty(); }
            @Override public List<ItemTemplate> findAll() { return List.of(); }
            @Override public Map<String, ItemTemplate> loadAll() { return Map.of(); }
            @Override public int upsert(ItemTemplate template) { return 0; }
            @Override public int deleteById(String itemId) { return 0; }
            @Override public int countShopReferences(String itemId) { return 0; }
        };
    }

    /** 모든 메서드가 빈 결과/0 을 반환하는 MonsterTemplateRepository. */
    public static MonsterTemplateRepository emptyMonsterRepo() {
        return new MonsterTemplateRepository() {
            @Override public Optional<MonsterTemplate> findById(String monsterId) { return Optional.empty(); }
            @Override public List<MonsterTemplate> findAll() { return List.of(); }
            @Override public Map<String, MonsterTemplate> loadAll() { return Map.of(); }
            @Override public int upsertTemplate(MonsterTemplate t) { return 0; }
            @Override public int replaceDrops(String monsterId, DropTable drops) { return 0; }
            @Override public int upsertDropLine(String monsterId, String itemId, double chance, int sortOrder) { return 0; }
            @Override public int deleteDropLine(String monsterId, String itemId) { return 0; }
            @Override public int deleteById(String monsterId) { return 0; }
        };
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

    /** 인메모리 MonsterTemplateRepository — 테스트 부트스트랩용. */
    private static final class InMemoryMonsterRepo implements MonsterTemplateRepository {
        private final Map<String, MonsterTemplate> data;
        InMemoryMonsterRepo(Map<String, MonsterTemplate> data) { this.data = data; }

        @Override public Optional<MonsterTemplate> findById(String id) {
            return Optional.ofNullable(data.get(id));
        }
        @Override public List<MonsterTemplate> findAll() { return List.copyOf(data.values()); }
        @Override public Map<String, MonsterTemplate> loadAll() { return Map.copyOf(data); }
        @Override public int upsertTemplate(MonsterTemplate t) { data.put(t.id(), t); return 1; }
        @Override public int replaceDrops(String monsterId, DropTable drops) { return drops.entries().size(); }
        @Override public int upsertDropLine(String monsterId, String itemId, double chance, int sortOrder) { return 1; }
        @Override public int deleteDropLine(String monsterId, String itemId) { return 1; }
        @Override public int deleteById(String monsterId) {
            return data.remove(monsterId) == null ? 0 : 1;
        }
    }

    /** 인메모리 ItemTemplateRepository — bootstrapDefaultItems 가 사용. */
    private static final class InMemoryItemRepo implements ItemTemplateRepository {
        private final Map<String, ItemTemplate> data;
        InMemoryItemRepo(Map<String, ItemTemplate> data) { this.data = data; }

        @Override public Optional<ItemTemplate> findById(String itemId) {
            return Optional.ofNullable(data.get(itemId));
        }
        @Override public List<ItemTemplate> findAll() { return List.copyOf(data.values()); }
        @Override public Map<String, ItemTemplate> loadAll() { return Map.copyOf(data); }
        @Override public int upsert(ItemTemplate t) {
            data.put(t.id(), t);
            return 1;
        }
        @Override public int deleteById(String itemId) {
            return data.remove(itemId) == null ? 0 : 1;
        }
        @Override public int countShopReferences(String itemId) { return 0; }
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
