package mygame.admin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;
import mygame.db.AccountRepository;
import mygame.db.PlayerRepository;
import mygame.db.PlayerRepository.PlayerData;
import mygame.game.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kick 명령의 세 분기(NO_PLAYER / NOT_ONLINE / KICKED) 모두에서 audit 가 정확한 state 를
 * 기록하고, KICKED 분기에서만 kickAction 이 호출되는지 검증.
 */
class KickPlayerCommandTest {

    @Test
    @DisplayName("KICKED: 인메모리 Player 가 발견되면 kickAction 호출 + audit state=KICKED")
    void online_kicks_and_audits() {
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var kicked = new AtomicReference<Player>(null);

        Player online = new Player(1, 7L, "alice", null, "henesys", 80, 100);
        var facade = new AdminFacade(
                () -> List.of(online),
                emptyAccountRepo(),
                playerRepoFor(online),
                recordingAudit(auditEntries),
                () -> {},
                kicked::set);

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        String msg = new KickPlayerCommand(facade, 42L)
                .execute(session, recordingAudit(auditEntries));

        assertSame(online, kicked.get(), "kickAction 은 정확히 그 Player 인스턴스로 호출돼야 함");
        assertEquals(1, auditEntries.size());
        assertEquals("PLAYER_KICK", auditEntries.get(0).action());
        assertTrue(auditEntries.get(0).payload().contains("\"state\":\"KICKED\""));
        assertTrue(auditEntries.get(0).payload().contains("\"playerName\":\"alice\""));
        assertTrue(msg.contains("킥 완료"));
    }

    @Test
    @DisplayName("NOT_ONLINE: 캐릭터는 있지만 sessionPlayers 에 없으면 kickAction 미호출")
    void offline_audits_only() {
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var kicked = new AtomicReference<Player>(null);

        // 인메모리 접속자 없음 + DB 에는 캐릭터 존재
        var snapshot = new PlayerData(7L, "bob", 1, 0, 0L, 100, 50, Map.of(), Map.of());
        var facade = new AdminFacade(
                List::of,
                emptyAccountRepo(),
                playerRepoFromSnapshot(snapshot),
                recordingAudit(auditEntries),
                () -> {},
                kicked::set);

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        String msg = new KickPlayerCommand(facade, 42L)
                .execute(session, recordingAudit(auditEntries));

        assertNull(kicked.get(), "오프라인 분기에서는 kickAction 이 호출되면 안 됨");
        assertTrue(auditEntries.get(0).payload().contains("\"state\":\"NOT_ONLINE\""));
        assertTrue(msg.contains("이미 오프라인"));
    }

    @Test
    @DisplayName("NO_PLAYER: 캐릭터 자체가 없으면 audit 만 남기고 종료")
    void no_player_audits_only() {
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var kicked = new AtomicReference<Player>(null);
        var facade = new AdminFacade(
                List::of,
                emptyAccountRepo(),
                emptyPlayerRepo(),
                recordingAudit(auditEntries),
                () -> {},
                kicked::set);

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        String msg = new KickPlayerCommand(facade, 999L)
                .execute(session, recordingAudit(auditEntries));

        assertNull(kicked.get());
        assertTrue(auditEntries.get(0).payload().contains("\"state\":\"NO_PLAYER\""));
        assertTrue(msg.contains("대상 캐릭터 없음"));
    }

    // --- helpers ---

    private static PlayerRepository playerRepoFor(Player online) {
        var snapshot = new PlayerData(online.dbId(), online.name(), 1, 0, 0L, 100, 50,
                Map.of(), Map.of());
        return playerRepoFromSnapshot(snapshot);
    }

    private static PlayerRepository playerRepoFromSnapshot(PlayerData snapshot) {
        return new PlayerRepository() {
            @Override public Optional<PlayerData> findByName(String name) { return Optional.empty(); }
            @Override public Optional<PlayerData> findByAccountId(long accountId) { return Optional.of(snapshot); }
            @Override public PlayerData create(String name, long accountId) {
                throw new UnsupportedOperationException();
            }
            @Override public void save(long id, int level, int exp, long meso, int hp, int mp,
                                       Map<String, Integer> items, Map<String, String> equipment) {}
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

    private static AuditLogRepository recordingAudit(List<AuditLogRepository.Entry> sink) {
        return new AuditLogRepository() {
            @Override public void append(Long adminId, String adminUsername, String action, String payload) {
                sink.add(new Entry(sink.size() + 1, adminId, adminUsername, action, payload, Instant.now()));
            }
            @Override public List<Entry> recent(int limit) { return List.copyOf(sink); }
        };
    }
}
