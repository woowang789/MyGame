package mygame.admin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mygame.admin.AdminFacade;
import mygame.admin.TestRepos;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;
import mygame.db.AccountRepository;
import mygame.db.PlayerRepository;
import mygame.db.PlayerRepository.PlayerData;
import mygame.db.ShopRepository;
import mygame.game.shop.ShopCatalog;
import mygame.game.shop.ShopCatalog.Entry;
import mygame.game.shop.ShopRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상점 카탈로그 upsert/delete 명령의 핵심 행동:
 * <ol>
 *   <li>Repository 위임이 정확한 인자로 호출.
 *   <li>실행 후 ShopRegistry 캐시가 reload — 다음 find() 가 새 가격을 반환.
 *   <li>audit payload 에 변경 내용 + updated 행 수 기록.
 * </ol>
 */
class ShopUpsertItemCommandTest {

    @BeforeEach
    void setUp() {
        // 매 테스트마다 깨끗한 카탈로그 + 같은 InMemoryShopRepo 인스턴스 재사용을 막기 위해
        // 기본 시드를 다시 깐다. (ShopRegistry 가 정적이라 다른 테스트가 남긴 상태가 영향 줄 수 있음)
        TestRepos.bootstrapDefaultShops();
    }

    @Test
    @DisplayName("upsert: repo 위임 + 캐시 reload → ShopRegistry.find 가 새 가격 반환 + audit SHOP_UPSERT_ITEM")
    void upsert_updates_cache_and_audit() {
        var capturedRepo = new CapturingShopRepo();
        // 인메모리 상점 시드: 'henesys_general' 이 있다고 가정.
        capturedRepo.put("henesys_general", new ShopCatalog("henesys_general", List.of(
                new Entry("red_potion", 50, 50)
        )));
        // ShopRegistry 가 이 repo 를 가리키도록 부트스트랩 — 명령 실행 후 reload 가 같은 repo
        // 에서 다시 읽도록 강제.
        ShopRegistry.bootstrap(capturedRepo);

        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = new AdminFacade(
                List::of, emptyAccountRepo(), emptyPlayerRepo(),
                capturedRepo,                        // Facade.upsertShopItem 이 이 repo 호출
                recordingAudit(auditEntries),
                () -> {}, p -> {}, m -> 0);

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        new ShopUpsertItemCommand(facade, "henesys_general", "red_potion", 999L, 30, 0)
                .execute(session, recordingAudit(auditEntries));

        // repo 위임 검증
        assertEquals("red_potion", capturedRepo.lastUpsertItemId);
        assertEquals(999L, capturedRepo.lastUpsertPrice);
        assertEquals(30, capturedRepo.lastUpsertStock);

        // 캐시 reload 검증 — ShopRegistry.find 가 갱신된 가격을 돌려줘야 함
        ShopCatalog after = ShopRegistry.find("henesys_general").orElseThrow();
        Entry updated = after.find("red_potion").orElseThrow();
        assertEquals(999L, updated.price(),
                "reload 후 캐시가 새 가격을 반영해야 함");
        assertEquals(30, updated.stockPerTransaction());

        // audit 검증
        assertEquals(1, auditEntries.size());
        assertEquals("SHOP_UPSERT_ITEM", auditEntries.get(0).action());
        String payload = auditEntries.get(0).payload();
        assertTrue(payload.contains("\"price\":999"));
        assertTrue(payload.contains("\"stockPerTx\":30"));
        assertTrue(payload.contains("\"updated\":1"));
    }

    @Test
    @DisplayName("delete: repo 위임 + 캐시 reload 후 find 가 해당 라인 미반환")
    void delete_removes_from_cache() {
        var capturedRepo = new CapturingShopRepo();
        capturedRepo.put("henesys_general", new ShopCatalog("henesys_general", List.of(
                new Entry("red_potion", 50, 50),
                new Entry("blue_potion", 80, 50)
        )));
        ShopRegistry.bootstrap(capturedRepo);

        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = new AdminFacade(
                List::of, emptyAccountRepo(), emptyPlayerRepo(),
                capturedRepo,
                recordingAudit(auditEntries),
                () -> {}, p -> {}, m -> 0);

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        new ShopDeleteItemCommand(facade, "henesys_general", "red_potion")
                .execute(session, recordingAudit(auditEntries));

        // 캐시 reload 검증 — 해당 itemId 가 더 이상 카탈로그에 없어야 함
        ShopCatalog after = ShopRegistry.find("henesys_general").orElseThrow();
        assertEquals(1, after.items().size(), "1개 라인 삭제 후 1개만 남아야 함");
        assertSame("blue_potion", after.items().get(0).itemId());

        // audit 검증
        assertEquals("SHOP_DELETE_ITEM", auditEntries.get(0).action());
        assertTrue(auditEntries.get(0).payload().contains("\"deleted\":1"));
    }

    // --- helpers ---

    /** AdminFacade 가 직접 잡는 ShopRepository — Capture 한 마지막 호출을 검증 가능. */
    private static final class CapturingShopRepo implements ShopRepository {
        private final java.util.concurrent.ConcurrentHashMap<String, ShopCatalog> data = new java.util.concurrent.ConcurrentHashMap<>();
        String lastUpsertItemId;
        long lastUpsertPrice;
        int lastUpsertStock;

        void put(String shopId, ShopCatalog c) { data.put(shopId, c); }

        @Override public Optional<ShopCatalog> findById(String shopId) {
            return Optional.ofNullable(data.get(shopId));
        }
        @Override public Optional<ShopSummary> findSummary(String shopId) {
            ShopCatalog c = data.get(shopId);
            return c == null ? Optional.empty() : Optional.of(new ShopSummary(shopId, "n", c.items().size()));
        }
        @Override public List<ShopSummary> findAllSummaries() {
            return data.values().stream()
                    .map(c -> new ShopSummary(c.shopId(), "n", c.items().size()))
                    .toList();
        }
        @Override public Map<String, ShopCatalog> loadAllCatalogs() { return Map.copyOf(data); }
        @Override public int upsertShop(String shopId, String name) { return 1; }
        @Override public int upsertItem(String shopId, String itemId,
                                        long price, int stockPerTx, int sortOrder) {
            lastUpsertItemId = itemId;
            lastUpsertPrice = price;
            lastUpsertStock = stockPerTx;
            ShopCatalog c = data.get(shopId);
            java.util.List<Entry> next = c == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(c.items());
            boolean replaced = false;
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i).itemId().equals(itemId)) {
                    next.set(i, new Entry(itemId, price, stockPerTx));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) next.add(new Entry(itemId, price, stockPerTx));
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

    private static AccountRepository emptyAccountRepo() {
        return new AccountRepository() {
            @Override public Optional<Account> findByUsername(String username) { return Optional.empty(); }
            @Override public Optional<AccountSummary> findById(long id) { return Optional.empty(); }
            @Override public Account create(String u, String h, String s) {
                throw new UnsupportedOperationException();
            }
            @Override public List<AccountSummary> findPage(int offset, int limit) { return List.of(); }
            @Override public long count() { return 0; }
            @Override public int setDisabled(long id, boolean d) { return 0; }
            @Override public int updatePassword(long id, String hash, String salt) { return 0; }
        };
    }

    private static PlayerRepository emptyPlayerRepo() {
        return new PlayerRepository() {
            @Override public Optional<PlayerData> findByName(String name) { return Optional.empty(); }
            @Override public Optional<PlayerData> findByAccountId(long accountId) { return Optional.empty(); }
            @Override public PlayerData create(String name, long accountId) {
                throw new UnsupportedOperationException();
            }
            @Override public void save(long id, int level, int exp, long meso, int hp, int mp,
                                       Map<String, Integer> items, Map<String, String> equipment) {}
        };
    }

    private static AuditLogRepository recordingAudit(List<AuditLogRepository.Entry> sink) {
        return new AuditLogRepository() {
            @Override public void append(Long adminId, String adminUsername, String action, String payload) {
                sink.add(new Entry(sink.size() + 1, adminId, adminUsername, action, payload, Instant.now()));
            }
            @Override public List<Entry> recent(int limit) { return List.copyOf(sink); }
        };
    }
}
