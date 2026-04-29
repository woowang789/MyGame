package mygame.admin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;
import mygame.db.AccountRepository;
import mygame.db.PlayerRepository;
import mygame.db.PlayerRepository.PlayerData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BAN/UNBAN 의 도메인 → audit 기록 → 결과 메시지 흐름을 한 번에 검증.
 */
class SetAccountDisabledCommandTest {

    @Test
    @DisplayName("BAN: setDisabled(true) 위임 + audit ACCOUNT_BAN 1건 + 정지 완료 메시지")
    void ban_records_audit() {
        var captured = new AtomicReference<Boolean>(null);
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = new AdminFacade(
                List::of,
                accountRepo(captured, 1),
                emptyPlayerRepo(),
                mygame.admin.TestRepos.emptyShopRepo(),
                recordingAudit(auditEntries),
                () -> {},
                p -> {},
                m -> 0);

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        var cmd = new SetAccountDisabledCommand(facade, 42L, true);
        String msg = cmd.execute(session, recordingAudit(auditEntries));

        assertEquals(true, captured.get(), "setDisabled(true) 가 위임되어야 함");
        assertEquals(1, auditEntries.size());
        assertEquals("ACCOUNT_BAN", auditEntries.get(0).action());
        assertTrue(auditEntries.get(0).payload().contains("\"accountId\":42"));
        assertTrue(msg.contains("정지 완료"));
    }

    @Test
    @DisplayName("UNBAN: setDisabled(false) 위임 + audit ACCOUNT_UNBAN")
    void unban_records_audit() {
        var captured = new AtomicReference<Boolean>(null);
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = new AdminFacade(
                List::of,
                accountRepo(captured, 1),
                emptyPlayerRepo(),
                mygame.admin.TestRepos.emptyShopRepo(),
                recordingAudit(auditEntries),
                () -> {},
                p -> {},
                m -> 0);

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        new SetAccountDisabledCommand(facade, 42L, false)
                .execute(session, recordingAudit(auditEntries));

        assertEquals(false, captured.get());
        assertEquals("ACCOUNT_UNBAN", auditEntries.get(0).action());
    }

    @Test
    @DisplayName("존재하지 않는 id: updated=0 audit + '대상 계정 없음' 메시지")
    void missing_account_records_zero_update() {
        var captured = new AtomicReference<Boolean>(null);
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var facade = new AdminFacade(
                List::of,
                accountRepo(captured, 0), // updated 0 반환
                emptyPlayerRepo(),
                mygame.admin.TestRepos.emptyShopRepo(),
                recordingAudit(auditEntries),
                () -> {},
                p -> {},
                m -> 0);

        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        String msg = new SetAccountDisabledCommand(facade, 999L, true)
                .execute(session, recordingAudit(auditEntries));

        assertEquals(1, auditEntries.size(), "잘못된 id 호출도 audit 1건 남아야 함");
        assertTrue(auditEntries.get(0).payload().contains("\"updated\":0"));
        assertTrue(msg.contains("대상 계정 없음"));
    }

    private static AccountRepository accountRepo(AtomicReference<Boolean> captured, int updateResult) {
        return new AccountRepository() {
            @Override public Optional<Account> findByUsername(String username) { return Optional.empty(); }
            @Override public Optional<AccountSummary> findById(long id) { return Optional.empty(); }
            @Override public Account create(String u, String h, String s) {
                throw new UnsupportedOperationException();
            }
            @Override public List<AccountSummary> findPage(int offset, int limit) { return List.of(); }
            @Override public long count() { return 0; }
            @Override public int setDisabled(long id, boolean d) {
                captured.set(d);
                return updateResult;
            }
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
                                       java.util.Map<String, Integer> items,
                                       java.util.Map<String, String> equipment) {}
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
