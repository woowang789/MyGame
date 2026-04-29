package mygame.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import mygame.admin.AdminFacade.OnlinePlayerView;
import mygame.admin.audit.AuditLogRepository;
import mygame.db.AccountRepository;
import mygame.db.PlayerRepository;
import mygame.db.PlayerRepository.PlayerData;
import mygame.game.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 가짜 컬렉션·Repo·Saver 로 {@link AdminFacade} 의 read API 와 forceSaveAll 위임을 검증.
 *
 * <p>핵심 의도: 핸들러 레이어가 본 클래스의 메서드만 의존하므로, 도메인 객체 없이도
 * 여기서 페이지네이션/스냅샷/위임 동작을 단위 테스트로 묶어둘 수 있다.
 */
class AdminFacadeTest {

    @Test
    @DisplayName("onlineCount/onlineSnapshot 는 supplier 가 반환하는 컬렉션을 그대로 노출")
    void online_supplier_works() {
        // 실제 Player 인스턴스를 만들기는 부담스러우므로 빈 컬렉션 케이스로 한정.
        // limit 큰 상황에서도 안전하게 빈 결과를 돌려주는지 확인.
        var facade = new AdminFacade(
                List::of,
                fakeAccountRepo(0),
                fakePlayerRepo(),
                fakeAuditRepo(),
                noopSave());

        assertEquals(0, facade.onlineCount());
        assertEquals(List.of(), facade.onlineSnapshot(50));
    }

    @Test
    @DisplayName("accountsPage 는 호출 인자를 그대로 전달, accountCount 는 합계 반환")
    void accounts_pagination_delegates() {
        var counter = new AtomicInteger(0);
        var repo = new AccountRepository() {
            @Override public Optional<Account> findByUsername(String username) { return Optional.empty(); }
            @Override public Optional<AccountSummary> findById(long id) { return Optional.empty(); }
            @Override public Account create(String u, String h, String s) {
                throw new UnsupportedOperationException();
            }
            @Override public List<AccountSummary> findPage(int offset, int limit) {
                counter.incrementAndGet();
                return List.of(new AccountSummary(1, "alice", Instant.parse("2026-01-01T00:00:00Z"), false));
            }
            @Override public long count() { return 42; }
            @Override public int setDisabled(long id, boolean d) { return 1; }
        };
        var facade = new AdminFacade(List::of, repo, fakePlayerRepo(), fakeAuditRepo(), noopSave());

        var page = facade.accountsPage(0, 20);
        assertEquals(1, counter.get(), "findPage 가 정확히 1회 호출돼야 함");
        assertEquals("alice", page.get(0).username());
        assertEquals(42L, facade.accountCount());
    }

    @Test
    @DisplayName("forceSaveAll 은 주입된 saveAction 을 정확히 1회 호출")
    void forceSave_delegates() {
        var calls = new AtomicInteger(0);
        var facade = new AdminFacade(List::of, fakeAccountRepo(0), fakePlayerRepo(), fakeAuditRepo(),
                () -> calls.incrementAndGet());

        facade.forceSaveAll();
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("playerDetailByAccount 는 PlayerRepository 를 한 번만 호출하고 결과를 그대로 노출")
    void playerDetail_delegates() {
        var captured = new AtomicInteger(0);
        var playerRepo = new PlayerRepository() {
            @Override public Optional<PlayerData> findByName(String name) { return Optional.empty(); }
            @Override public Optional<PlayerData> findByAccountId(long accountId) {
                captured.incrementAndGet();
                return Optional.of(new PlayerData(7L, "alice-char", 12, 3400, 999L, 50, 30,
                        java.util.Map.of("snail_shell", 3),
                        java.util.Map.of("WEAPON", "sword_basic")));
            }
            @Override public PlayerData create(String name, long accountId) {
                throw new UnsupportedOperationException();
            }
            @Override public void save(long id, int level, int exp, long meso, int hp, int mp,
                                       java.util.Map<String, Integer> items,
                                       java.util.Map<String, String> equipment) {
                throw new UnsupportedOperationException();
            }
        };
        var facade = new AdminFacade(List::of, fakeAccountRepo(0), playerRepo, fakeAuditRepo(), noopSave());
        var detail = facade.playerDetailByAccount(42L);
        assertTrue(detail.isPresent());
        assertEquals("alice-char", detail.get().name());
        assertEquals(1, captured.get());
    }

    /** 모든 메서드 미구현 가짜 — playerDetail 미관여 테스트 케이스에서 사용. */
    private static PlayerRepository fakePlayerRepo() {
        return new PlayerRepository() {
            @Override public Optional<PlayerData> findByName(String name) { return Optional.empty(); }
            @Override public Optional<PlayerData> findByAccountId(long accountId) { return Optional.empty(); }
            @Override public PlayerData create(String name, long accountId) {
                throw new UnsupportedOperationException();
            }
            @Override public void save(long id, int level, int exp, long meso, int hp, int mp,
                                       java.util.Map<String, Integer> items,
                                       java.util.Map<String, String> equipment) {}
        };
    }

    @Test
    @DisplayName("stats 는 양수 heap/uptime 을 보고하고 onlineCount 를 함께 노출")
    void stats_shape() {
        var facade = new AdminFacade(List::of, fakeAccountRepo(0), fakePlayerRepo(), fakeAuditRepo(), noopSave());
        var stats = facade.stats();
        assertTrue(stats.heapUsedMb() >= 0);
        assertTrue(stats.heapMaxMb() >= stats.heapUsedMb());
        assertTrue(stats.uptimeSeconds() >= 0);
        assertEquals(0, stats.onlineCount());
    }

    // --- helpers ---

    private static AccountRepository fakeAccountRepo(long total) {
        return new AccountRepository() {
            @Override public Optional<Account> findByUsername(String username) { return Optional.empty(); }
            @Override public Optional<AccountSummary> findById(long id) { return Optional.empty(); }
            @Override public Account create(String u, String h, String s) {
                throw new UnsupportedOperationException();
            }
            @Override public List<AccountSummary> findPage(int offset, int limit) { return List.of(); }
            @Override public long count() { return total; }
            @Override public int setDisabled(long id, boolean d) { return 1; }
        };
    }

    private static AuditLogRepository fakeAuditRepo() {
        return new AuditLogRepository() {
            private final List<Entry> entries = new ArrayList<>();
            @Override public void append(Long adminId, String adminUsername, String action, String payload) {
                entries.add(new Entry(entries.size() + 1, adminId, adminUsername, action, payload, Instant.now()));
            }
            @Override public List<Entry> recent(int limit) {
                int from = Math.max(0, entries.size() - limit);
                return new ArrayList<>(entries.subList(from, entries.size()));
            }
        };
    }

    private static Runnable noopSave() {
        return () -> { /* no-op */ };
    }

    // 컴파일러가 미사용 import 를 잡지 못하도록 silly reference (Player 는 본 테스트에서 직접 사용 안 함)
    @SuppressWarnings("unused")
    private static Class<?> playerClassPin() { return Player.class; }

    @SuppressWarnings("unused")
    private static OnlinePlayerView pin() { return new OnlinePlayerView(0, "x", "y", 1, 1, 1); }
}
