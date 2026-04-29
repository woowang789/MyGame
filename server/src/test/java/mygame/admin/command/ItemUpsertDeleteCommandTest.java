package mygame.admin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import mygame.db.ItemTemplateRepository;
import mygame.db.PlayerRepository;
import mygame.db.PlayerRepository.PlayerData;
import mygame.game.item.EquipSlot;
import mygame.game.item.ItemRegistry;
import mygame.game.item.ItemTemplate;
import mygame.game.item.ItemTemplate.ItemType;
import mygame.game.item.ItemTemplate.UseEffect;
import mygame.game.stat.Stats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ItemUpsert/Delete 명령의 핵심 행동:
 * <ul>
 *   <li>upsert 후 ItemRegistry 캐시가 reload — 다음 get() 이 새 값 반환.
 *   <li>delete 는 shop_items 참조 시 차단 + audit 에 차단 사유 기록.
 *   <li>delete 성공 시 캐시에서도 제거.
 * </ul>
 */
class ItemUpsertDeleteCommandTest {

    @BeforeEach
    void setUp() {
        // 매 테스트 격리 — 다른 테스트가 남긴 캐시 제거.
        TestRepos.bootstrapDefaultItems();
    }

    @Test
    @DisplayName("ITEM_UPSERT: 새 EQUIPMENT 추가 → ItemRegistry.get 이 즉시 반환")
    void upsert_reloads_cache() {
        var capturing = new CapturingItemRepo();
        // 캐시가 capturing 을 가리키도록 부트스트랩 (reload 가 capturing 에서 읽도록)
        ItemRegistry.bootstrap(capturing);

        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = new AdminFacade(
                List::of, emptyAccountRepo(), emptyPlayerRepo(),
                TestRepos.emptyShopRepo(),
                capturing,
                mygame.admin.TestRepos.emptyMonsterRepo(),
                recordingAudit(auditEntries),
                () -> {}, p -> {}, m -> 0);

        ItemTemplate t = new ItemTemplate("magic_wand", "마법 지팡이", 0x9b59b6,
                ItemType.EQUIPMENT, EquipSlot.WEAPON, new Stats(0, 20, 35, 0), 1500L);
        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        new ItemUpsertCommand(facade, t).execute(session, recordingAudit(auditEntries));

        // 캐시 reload 확인
        ItemTemplate cached = ItemRegistry.get("magic_wand");
        assertEquals("마법 지팡이", cached.name());
        assertEquals(35, cached.bonus().attack());

        // audit 확인
        assertEquals("ITEM_UPSERT", auditEntries.get(0).action());
        assertTrue(auditEntries.get(0).payload().contains("\"type\":\"EQUIPMENT\""));
    }

    @Test
    @DisplayName("ITEM_DELETE: 참조 0 이면 삭제 + 캐시 reload 후 get IAE")
    void delete_with_no_references() {
        var capturing = new CapturingItemRepo();
        capturing.upsert(new ItemTemplate("expendable", "임시", 0,
                ItemType.ETC, 0L));
        capturing.shopRefs.put("expendable", 0); // 참조 없음
        ItemRegistry.bootstrap(capturing);

        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = new AdminFacade(
                List::of, emptyAccountRepo(), emptyPlayerRepo(),
                TestRepos.emptyShopRepo(),
                capturing,
                mygame.admin.TestRepos.emptyMonsterRepo(),
                recordingAudit(auditEntries),
                () -> {}, p -> {}, m -> 0);

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        String msg = new ItemDeleteCommand(facade, "expendable")
                .execute(session, recordingAudit(auditEntries));

        assertTrue(msg.contains("삭제 완료"));
        // 캐시에서도 제거
        try { ItemRegistry.get("expendable"); throw new AssertionError("should throw"); }
        catch (IllegalArgumentException ok) { /* 기대 */ }
        assertTrue(auditEntries.get(0).payload().contains("\"deleted\":true"));
    }

    @Test
    @DisplayName("ITEM_DELETE: shop_items 참조 시 차단 + audit 에 shopReferences 기록")
    void delete_blocked_when_referenced() {
        var capturing = new CapturingItemRepo();
        capturing.shopRefs.put("red_potion", 2); // 두 상점이 참조 중이라고 가정
        ItemRegistry.bootstrap(capturing);

        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = new AdminFacade(
                List::of, emptyAccountRepo(), emptyPlayerRepo(),
                TestRepos.emptyShopRepo(),
                capturing,
                mygame.admin.TestRepos.emptyMonsterRepo(),
                recordingAudit(auditEntries),
                () -> {}, p -> {}, m -> 0);

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        String msg = new ItemDeleteCommand(facade, "red_potion")
                .execute(session, recordingAudit(auditEntries));

        assertTrue(msg.contains("차단"));
        assertTrue(msg.contains("2"), "참조 수가 메시지에 표시돼야 함");
        // 영속화도 캐시도 건드리지 않았는지 — capturing.deletedIds 에 기록되지 않음.
        assertFalse(capturing.deletedIds.contains("red_potion"));
        assertTrue(auditEntries.get(0).payload().contains("\"shopReferences\":2"));
        assertTrue(auditEntries.get(0).payload().contains("\"deleted\":false"));
    }

    // --- helpers ---

    private static final class CapturingItemRepo implements ItemTemplateRepository {
        private final java.util.Map<String, ItemTemplate> data = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, Integer> shopRefs = new java.util.HashMap<>();
        private final java.util.Set<String> deletedIds = new java.util.HashSet<>();

        @Override public Optional<ItemTemplate> findById(String itemId) {
            return Optional.ofNullable(data.get(itemId));
        }
        @Override public List<ItemTemplate> findAll() { return List.copyOf(data.values()); }
        @Override public Map<String, ItemTemplate> loadAll() { return Map.copyOf(data); }
        @Override public int upsert(ItemTemplate t) { data.put(t.id(), t); return 1; }
        @Override public int deleteById(String itemId) {
            deletedIds.add(itemId);
            return data.remove(itemId) == null ? 0 : 1;
        }
        @Override public int countShopReferences(String itemId) {
            return shopRefs.getOrDefault(itemId, 0);
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

    @SuppressWarnings("unused") private static UseEffect pin() { return new UseEffect(0, 0); }
}
