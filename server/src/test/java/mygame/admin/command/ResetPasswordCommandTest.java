package mygame.admin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;
import mygame.auth.AuthService;
import mygame.auth.AuthService.AuthSuccess;
import mygame.auth.PasswordHasher;
import mygame.auth.PasswordHasher.Hashed;
import mygame.db.AccountRepository;
import mygame.db.PlayerRepository;
import mygame.db.PlayerRepository.PlayerData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 비밀번호 리셋의 핵심 보장:
 * <ol>
 *   <li>리셋 후 새 비밀번호로 AuthService 로그인 통과 / 옛 비밀번호로는 실패.
 *   <li>audit payload 가 비밀번호 자체나 길이를 절대 노출하지 않는다.
 *   <li>존재하지 않는 id 면 updated=0 + 안내 메시지.
 * </ol>
 */
class ResetPasswordCommandTest {

    @Test
    @DisplayName("리셋 → 새 비밀번호로 AuthService 로그인 성공, 옛 비밀번호 실패")
    void reset_then_login_with_new_password() {
        var repo = new InMemoryAccountRepo();
        Hashed h = PasswordHasher.hash("old-pass-123");
        var account = repo.create("alice", h.hash(), h.salt());

        // 사전 검증: 옛 비밀번호로 로그인 가능
        assertInstanceOf(AuthSuccess.class, new AuthService(repo).login("alice", "old-pass-123"));

        var facade = facade(repo);
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        new ResetPasswordCommand(facade, account.id(), "new-pass-456")
                .execute(session, recordingAudit(auditEntries));

        // 새 비밀번호 통과 / 옛 비밀번호 거부
        assertInstanceOf(AuthSuccess.class, new AuthService(repo).login("alice", "new-pass-456"));
        assertFalse(new AuthService(repo).login("alice", "old-pass-123") instanceof AuthSuccess);
    }

    @Test
    @DisplayName("audit payload 는 비밀번호·비밀번호 길이를 절대 포함하지 않는다")
    void audit_does_not_leak_password() {
        var repo = new InMemoryAccountRepo();
        Hashed h = PasswordHasher.hash("old-pass-123");
        var account = repo.create("bob", h.hash(), h.salt());

        var facade = facade(repo);
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));
        String secret = "TopSecret#2026";
        new ResetPasswordCommand(facade, account.id(), secret)
                .execute(session, recordingAudit(auditEntries));

        assertEquals(1, auditEntries.size());
        String payload = auditEntries.get(0).payload();
        assertFalse(payload.contains(secret), "비밀번호 평문이 audit 에 들어가면 안 됨");
        // 길이 단서도 흘리지 않는다 — payload 에 "length" 같은 키가 없어야.
        assertFalse(payload.toLowerCase().contains("length"));
        // accountId 와 updated 만 포함 — 운영자 사고 분석에 충분, 그 이상은 흘리지 않음.
        assertTrue(payload.contains("\"accountId\""));
        assertTrue(payload.contains("\"updated\""));
    }

    @Test
    @DisplayName("존재하지 않는 id: updated=0 audit + '대상 계정 없음' 메시지")
    void missing_account() {
        var repo = new InMemoryAccountRepo();
        var facade = facade(repo);
        var auditEntries = new ArrayList<AuditLogRepository.Entry>();
        var session = new Session("tok", 1L, "ops", "admin", Instant.now().plusSeconds(60));

        String msg = new ResetPasswordCommand(facade, 9999L, "any-pass-12")
                .execute(session, recordingAudit(auditEntries));

        assertEquals(1, auditEntries.size());
        assertTrue(auditEntries.get(0).payload().contains("\"updated\":0"));
        assertTrue(msg.contains("대상 계정 없음"));
    }

    // --- helpers ---

    private static AdminFacade facade(AccountRepository repo) {
        return new AdminFacade(List::of, repo, emptyPlayerRepo(), recordingAudit(new ArrayList<>()), () -> {});
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

    /** AuthService 와 ResetPassword 가 함께 동작하는지 보려고 — 인메모리 통합 가짜. */
    private static final class InMemoryAccountRepo implements AccountRepository {
        private final java.util.concurrent.ConcurrentHashMap<Long, Account> byId = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, Long> idByName = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicReference<Long> seq = new AtomicReference<>(0L);

        @Override public Optional<Account> findByUsername(String username) {
            Long id = idByName.get(username);
            return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
        }
        @Override public Optional<AccountSummary> findById(long accountId) {
            Account a = byId.get(accountId);
            if (a == null) return Optional.empty();
            return Optional.of(new AccountSummary(a.id(), a.username(), null, a.disabled()));
        }
        @Override public Account create(String username, String passwordHash, String salt) {
            long id = seq.updateAndGet(v -> v + 1);
            Account a = new Account(id, username, passwordHash, salt, false);
            byId.put(id, a);
            idByName.put(username, id);
            return a;
        }
        @Override public List<AccountSummary> findPage(int offset, int limit) { return List.of(); }
        @Override public long count() { return byId.size(); }
        @Override public int setDisabled(long accountId, boolean disabled) {
            Account a = byId.get(accountId);
            if (a == null) return 0;
            byId.put(accountId, new Account(a.id(), a.username(), a.passwordHash(), a.salt(), disabled));
            return 1;
        }
        @Override public int updatePassword(long accountId, String passwordHash, String salt) {
            Account a = byId.get(accountId);
            if (a == null) return 0;
            byId.put(accountId, new Account(a.id(), a.username(), passwordHash, salt, a.disabled()));
            return 1;
        }
    }
}
