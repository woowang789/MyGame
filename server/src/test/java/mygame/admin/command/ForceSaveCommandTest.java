package mygame.admin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;
import mygame.db.AccountRepository;
import mygame.db.PlayerRepository;
import mygame.db.PlayerRepository.PlayerData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 강제 저장 커맨드의 책임 분리 검증:
 * <ol>
 *   <li>AdminFacade.forceSaveAll() 을 정확히 1회 호출.
 *   <li>실행 후 audit 로그에 actor 정보와 onlineCount payload 를 기록.
 *   <li>execute 의 반환 메시지가 사용자에게 보여줄 만한 텍스트인지.
 * </ol>
 */
class ForceSaveCommandTest {

    @Test
    @DisplayName("execute 는 saveAction 을 1회 호출하고 audit 에 1건 기록한다")
    void executes_and_audits() {
        var saveCalls = new AtomicInteger(0);
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var auditRepo = recordingAuditRepo(auditEntries);

        var facade = new AdminFacade(
                List::of,           // 접속자 0명 케이스
                emptyAccountRepo(),
                emptyPlayerRepo(),
                mygame.admin.TestRepos.emptyShopRepo(),
                mygame.admin.TestRepos.emptyItemRepo(),
                mygame.admin.TestRepos.emptyMonsterRepo(),
                auditRepo,
                saveCalls::incrementAndGet,
                p -> {},
                m -> 0);

        var actor = new Session("tok-xyz", 7L, "ops", "admin",
                Instant.now().plusSeconds(3600));
        var cmd = new ForceSaveCommand(facade);

        String message = cmd.execute(actor, auditRepo);

        assertEquals(1, saveCalls.get(), "saveAll 은 정확히 1회 호출");
        assertEquals(1, auditEntries.size(), "audit 1건 기록");
        var entry = auditEntries.get(0);
        assertEquals("FORCE_SAVE", entry.action());
        assertEquals(7L, entry.adminId());
        assertEquals("ops", entry.adminUsername());
        assertTrue(entry.payload().contains("\"onlineCount\""), "payload 에 onlineCount 포함");
        assertTrue(message.contains("강제 저장"), "사용자용 메시지가 의미를 담아야 함");
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
            @Override public int setDisabled(long id, boolean d) { return 1; }
            @Override public int updatePassword(long id, String hash, String salt) { return 1; }
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

    private static AuditLogRepository recordingAuditRepo(List<AuditLogRepository.Entry> sink) {
        return new AuditLogRepository() {
            @Override public void append(Long adminId, String adminUsername, String action, String payload) {
                sink.add(new Entry(sink.size() + 1, adminId, adminUsername, action, payload, Instant.now()));
            }
            @Override public List<Entry> recent(int limit) { return List.copyOf(sink); }
        };
    }
}
