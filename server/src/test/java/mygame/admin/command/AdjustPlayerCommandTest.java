package mygame.admin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;
import mygame.admin.command.AdjustPlayerCommand.Kind;
import mygame.db.AccountRepository;
import mygame.db.PlayerRepository;
import mygame.db.PlayerRepository.PlayerData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 오프라인 분기에서 BAdjustPlayerCommand 가:
 * <ol>
 *   <li>PlayerRepository.save 에 정확한 새 값(클램프 포함)을 기록하고,
 *   <li>audit 1 건을 ADJUST_MESO/ADJUST_EXP 키로 남기는지
 * </ol>
 * 를 회귀 테스트로 고정한다. 온라인 분기는 인메모리 Player 가 필요해 별도 통합 케이스에서 다룸.
 */
class AdjustPlayerCommandTest {

    @Test
    @DisplayName("MESO 양수: PlayerRepository.save 에 누적된 새 메소를 쓰고 audit ADJUST_MESO 1건")
    void meso_offline_saves_and_audits() {
        var captured = new AtomicReference<Long>(null);
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();

        var facade = new AdminFacade(
                List::of, // 접속자 0 → 자동으로 offline 경로
                emptyAccountRepo(),
                playerRepoStubMeso(500L, captured),
                recordingAudit(auditEntries),
                () -> {},
                p -> {});

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        String msg = new AdjustPlayerCommand(facade, 7L, Kind.MESO, 1500L)
                .execute(session, recordingAudit(auditEntries));

        assertEquals(2000L, captured.get(), "기존 500 + delta 1500 = 2000 이 저장돼야 함");
        assertEquals(1, auditEntries.size());
        assertEquals("ADJUST_MESO", auditEntries.get(0).action());
        assertTrue(auditEntries.get(0).payload().contains("\"delta\":1500"));
        assertTrue(auditEntries.get(0).payload().contains("\"newValue\":2000"));
        assertTrue(msg.contains("오프라인 DB"));
    }

    @Test
    @DisplayName("MESO 음수 큰 차감: 0 으로 클램프되어 저장")
    void meso_offline_clamps_negative() {
        var captured = new AtomicReference<Long>(null);
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = new AdminFacade(
                List::of,
                emptyAccountRepo(),
                playerRepoStubMeso(100L, captured),
                recordingAudit(auditEntries),
                () -> {},
                p -> {});

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        new AdjustPlayerCommand(facade, 7L, Kind.MESO, -99999L)
                .execute(session, recordingAudit(auditEntries));

        assertEquals(0L, captured.get(), "0 미만으로 떨어지면 0 으로 클램프되어야 함");
    }

    @Test
    @DisplayName("EXP: int 캐스트 + 클램프, audit ADJUST_EXP 키 사용")
    void exp_offline_audit_key() {
        var captured = new AtomicReference<Long>(null);
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = new AdminFacade(
                List::of,
                emptyAccountRepo(),
                playerRepoStubExp(50, captured),
                recordingAudit(auditEntries),
                () -> {},
                p -> {});

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        new AdjustPlayerCommand(facade, 7L, Kind.EXP, 200L)
                .execute(session, recordingAudit(auditEntries));

        assertEquals(250L, captured.get());
        assertEquals("ADJUST_EXP", auditEntries.get(0).action());
    }

    @Test
    @DisplayName("플레이어 미존재: save 미호출 + audit 에 playerExists=false 기록")
    void no_player_records_audit_only() {
        var captured = new AtomicReference<Long>(null);
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = new AdminFacade(
                List::of,
                emptyAccountRepo(),
                emptyPlayerRepo(captured),
                recordingAudit(auditEntries),
                () -> {},
                p -> {});

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        String msg = new AdjustPlayerCommand(facade, 999L, Kind.MESO, 100L)
                .execute(session, recordingAudit(auditEntries));

        assertEquals(null, captured.get(), "save 가 호출되면 안 됨");
        assertEquals(1, auditEntries.size());
        assertTrue(auditEntries.get(0).payload().contains("\"playerExists\":false"));
        assertTrue(msg.contains("대상 캐릭터 없음"));
    }

    // --- helpers ---

    private static PlayerRepository playerRepoStubMeso(long initialMeso, AtomicReference<Long> savedMeso) {
        PlayerData snapshot = new PlayerData(7L, "alice", 1, 0, initialMeso, 100, 50,
                Map.of(), Map.of());
        return new PlayerRepository() {
            @Override public Optional<PlayerData> findByName(String name) { return Optional.empty(); }
            @Override public Optional<PlayerData> findByAccountId(long accountId) { return Optional.of(snapshot); }
            @Override public PlayerData create(String name, long accountId) {
                throw new UnsupportedOperationException();
            }
            @Override public void save(long id, int level, int exp, long meso, int hp, int mp,
                                       Map<String, Integer> items, Map<String, String> equipment) {
                savedMeso.set(meso);
            }
        };
    }

    private static PlayerRepository playerRepoStubExp(int initialExp, AtomicReference<Long> savedExp) {
        PlayerData snapshot = new PlayerData(7L, "alice", 1, initialExp, 0L, 100, 50,
                Map.of(), Map.of());
        return new PlayerRepository() {
            @Override public Optional<PlayerData> findByName(String name) { return Optional.empty(); }
            @Override public Optional<PlayerData> findByAccountId(long accountId) { return Optional.of(snapshot); }
            @Override public PlayerData create(String name, long accountId) {
                throw new UnsupportedOperationException();
            }
            @Override public void save(long id, int level, int exp, long meso, int hp, int mp,
                                       Map<String, Integer> items, Map<String, String> equipment) {
                savedExp.set((long) exp);
            }
        };
    }

    private static PlayerRepository emptyPlayerRepo(AtomicReference<Long> sentinel) {
        return new PlayerRepository() {
            @Override public Optional<PlayerData> findByName(String name) { return Optional.empty(); }
            @Override public Optional<PlayerData> findByAccountId(long accountId) { return Optional.empty(); }
            @Override public PlayerData create(String name, long accountId) {
                throw new UnsupportedOperationException();
            }
            @Override public void save(long id, int level, int exp, long meso, int hp, int mp,
                                       Map<String, Integer> items, Map<String, String> equipment) {
                sentinel.set(meso);
            }
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

    @SuppressWarnings("unused") private static Map<String, Integer> unused() { return new HashMap<>(); }
}
