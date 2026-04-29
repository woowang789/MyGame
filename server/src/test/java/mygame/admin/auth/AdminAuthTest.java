package mygame.admin.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import mygame.auth.PasswordHasher;
import mygame.auth.PasswordHasher.Hashed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AdminAuth} 의 핵심 행동 검증:
 * <ul>
 *   <li>bootstrapIfEmpty 는 비어 있을 때 1회 시드, 채워져 있으면 호출 무시.
 *   <li>로그인 성공 시 새 토큰 발급, 동일 토큰으로 resolve 가능.
 *   <li>잘못된 비밀번호는 빈 결과.
 *   <li>logout 후 토큰 무효화.
 * </ul>
 */
class AdminAuthTest {

    @Test
    @DisplayName("bootstrapIfEmpty: 비어있고 env 가 valid 하면 admin 1명 시드")
    void bootstrap_seeds_once() {
        var repo = new InMemoryAdminRepo();
        var auth = new AdminAuth(repo);

        auth.bootstrapIfEmpty("admin:correct-horse-battery");
        assertEquals(1, repo.count());

        // 다시 호출해도 누적 증가 없음.
        auth.bootstrapIfEmpty("other:another");
        assertEquals(1, repo.count());
    }

    @Test
    @DisplayName("bootstrapIfEmpty: env 미설정/형식 오류는 시드하지 않는다")
    void bootstrap_invalid_env_skipped() {
        var repo = new InMemoryAdminRepo();
        var auth = new AdminAuth(repo);

        auth.bootstrapIfEmpty(null);
        auth.bootstrapIfEmpty("");
        auth.bootstrapIfEmpty("nocolon");
        auth.bootstrapIfEmpty(":empty-user");
        auth.bootstrapIfEmpty("empty-pass:");
        assertEquals(0, repo.count());
    }

    @Test
    @DisplayName("login 성공/실패 + resolve + logout 의 전체 사이클")
    void login_resolve_logout() {
        var repo = new InMemoryAdminRepo();
        Hashed h = PasswordHasher.hash("p@ss");
        repo.create("ops", h.hash(), h.salt(), "admin");
        var auth = new AdminAuth(repo);

        // 잘못된 비밀번호 → empty
        assertTrue(auth.login("ops", "wrong").isEmpty());
        // 미등록 사용자 → empty (타이밍 균등 위해 dummy verify 도 통과해야 하지만 결과는 empty)
        assertTrue(auth.login("ghost", "p@ss").isEmpty());

        // 정상 로그인
        var session = auth.login("ops", "p@ss");
        assertTrue(session.isPresent());
        assertEquals("ops", session.get().username());
        assertEquals("admin", session.get().role());

        // 같은 토큰으로 resolve 가능
        Optional<AdminAuth.Session> resolved = auth.resolve(session.get().token());
        assertTrue(resolved.isPresent());
        assertEquals(session.get().username(), resolved.get().username());

        // logout 후엔 동일 토큰 무효
        auth.logout(session.get().token());
        assertFalse(auth.resolve(session.get().token()).isPresent());
    }

    /** 인메모리 admin 리포지토리 — 테스트 격리. */
    private static final class InMemoryAdminRepo implements AdminAccountRepository {
        private final ConcurrentHashMap<String, AdminAccount> byUsername = new ConcurrentHashMap<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override public Optional<AdminAccount> findByUsername(String username) {
            return Optional.ofNullable(byUsername.get(username));
        }
        @Override public AdminAccount create(String username, String passwordHash, String salt, String role) {
            var a = new AdminAccount(seq.getAndIncrement(), username, passwordHash, salt, role);
            byUsername.put(username, a);
            return a;
        }
        @Override public long count() { return byUsername.size(); }
    }
}
