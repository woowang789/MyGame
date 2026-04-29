package mygame.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import mygame.auth.AuthService.AuthFailure;
import mygame.auth.AuthService.AuthSuccess;
import mygame.auth.PasswordHasher.Hashed;
import mygame.db.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 인증 서비스의 핵심 결정 분기 — 특히 disabled 분기를 회귀 테스트로 고정한다.
 *
 * <p>왜 이 테스트가 중요한가: 백오피스에서 정지한 계정이 실제로 로그인 거부되는지가
 * 정지 기능의 본질이고, 이 행동을 코드 수준에서 못 박지 않으면 추후 인증 흐름 리팩터로
 * 조용히 깨질 수 있다.
 */
class AuthServiceTest {

    @Test
    @DisplayName("정상 비밀번호 + disabled=false → 성공")
    void login_success_when_active() {
        var repo = new InMemoryAccountRepo();
        Hashed h = PasswordHasher.hash("p@ss-1234");
        repo.create("alice", h.hash(), h.salt());

        var result = new AuthService(repo).login("alice", "p@ss-1234");
        var success = assertInstanceOf(AuthSuccess.class, result);
        assertEquals("alice", success.account().username());
    }

    @Test
    @DisplayName("정상 비밀번호 + disabled=true → 인증 실패 메시지에 정지 안내")
    void login_rejected_when_disabled() {
        var repo = new InMemoryAccountRepo();
        Hashed h = PasswordHasher.hash("p@ss-1234");
        var created = repo.create("bob", h.hash(), h.salt());
        repo.setDisabled(created.id(), true);

        var result = new AuthService(repo).login("bob", "p@ss-1234");
        var failure = assertInstanceOf(AuthFailure.class, result);
        assertTrue(failure.message().contains("정지"),
                "실패 메시지에 정지 안내가 있어야 한다 — got: " + failure.message());
    }

    @Test
    @DisplayName("틀린 비밀번호 + disabled=true → 정지 메시지가 아닌 일반 인증 실패 노출 (정지 여부 떠보기 차단)")
    void login_wrongPassword_doesNotLeakDisabledFlag() {
        var repo = new InMemoryAccountRepo();
        Hashed h = PasswordHasher.hash("p@ss-1234");
        var created = repo.create("carol", h.hash(), h.salt());
        repo.setDisabled(created.id(), true);

        var result = new AuthService(repo).login("carol", "WRONG");
        var failure = assertInstanceOf(AuthFailure.class, result);
        // 비밀번호 검증 실패 단계에서 끊겨야 하므로 메시지에 "정지" 가 들어가면 안 된다.
        assertTrue(failure.message().contains("올바르지 않"),
                "비밀번호 실패 메시지여야 함 — got: " + failure.message());
        assertTrue(!failure.message().contains("정지"),
                "정지 여부가 비밀번호로 떠보기 가능하면 안 됨 — got: " + failure.message());
    }

    /** 인메모리 AccountRepository — 테스트 격리. */
    private static final class InMemoryAccountRepo implements AccountRepository {
        private final ConcurrentHashMap<Long, Account> byId = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Long> idByName = new ConcurrentHashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override public Optional<Account> findByUsername(String username) {
            Long id = idByName.get(username);
            return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
        }
        @Override public Account create(String username, String passwordHash, String salt) {
            long id = seq.getAndIncrement();
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
    }
}
