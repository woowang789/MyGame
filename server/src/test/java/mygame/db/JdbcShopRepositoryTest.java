package mygame.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 인메모리 H2 위에서 {@link JdbcShopRepository} 의 CRUD + MERGE upsert 동작을 검증.
 * v9/v10 마이그레이션이 자동 적용되므로 'henesys_general' 시드 행이 들어 있다.
 */
class JdbcShopRepositoryTest {

    private Database db;
    private JdbcShopRepository repo;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:shop-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        db = new Database(url, "sa", "");
        Migrations.apply(db);
        repo = new JdbcShopRepository(db);
    }

    @AfterEach
    void tearDown() { db.close(); }

    @Test
    @DisplayName("부트스트랩 시드: henesys_general 카탈로그가 4개 라인으로 적재")
    void seedRowsLoaded() {
        var summary = repo.findSummary("henesys_general").orElseThrow();
        assertEquals("잡화상 페트라", summary.name());
        assertEquals(4, summary.itemCount());

        var catalog = repo.findById("henesys_general").orElseThrow();
        // sort_order 순서: red_potion, blue_potion, wooden_sword, leather_cap
        assertEquals("red_potion", catalog.items().get(0).itemId());
        assertEquals(50L, catalog.items().get(0).price());
        assertEquals(50, catalog.items().get(0).stockPerTransaction());
    }

    @Test
    @DisplayName("upsertItem: 같은 (shopId,itemId) 두 번 → 두 번째 호출이 가격 갱신")
    void upsertReplacesExisting() {
        repo.upsertItem("henesys_general", "red_potion", 50, 50, 0); // 시드와 동일
        repo.upsertItem("henesys_general", "red_potion", 999, 25, 0); // 갱신

        var entry = repo.findById("henesys_general").orElseThrow().find("red_potion").orElseThrow();
        assertEquals(999L, entry.price());
        assertEquals(25, entry.stockPerTransaction());
    }

    @Test
    @DisplayName("deleteItem: 라인 삭제 후 카탈로그에서 사라진다")
    void deleteRemovesLine() {
        int affected = repo.deleteItem("henesys_general", "red_potion");
        assertEquals(1, affected);
        assertTrue(repo.findById("henesys_general").orElseThrow().find("red_potion").isEmpty());
        assertEquals(3, repo.findSummary("henesys_general").orElseThrow().itemCount());
    }

    @Test
    @DisplayName("loadAllCatalogs: 모든 상점을 한 번에 적재 (캐시 부트스트랩 시나리오)")
    void loadAllCatalogs_includesEmptyShop() {
        // 빈 shop 도 누락되지 않는지 확인 — 시드와 별개로 새 shop 추가 후 items 없이 조회.
        repo.upsertShop("empty_shop", "비어있는 상점");
        var all = repo.loadAllCatalogs();
        assertTrue(all.containsKey("henesys_general"));
        assertTrue(all.containsKey("empty_shop"), "shop_items 가 비어도 빈 카탈로그로 노출");
        assertEquals(0, all.get("empty_shop").items().size());
    }

    @Test
    @DisplayName("미존재 shopId 는 빈 결과")
    void unknownShopId() {
        assertFalse(repo.findById("nope").isPresent());
        assertFalse(repo.findSummary("nope").isPresent());
        assertEquals(0, repo.deleteItem("nope", "anything"));
    }
}
