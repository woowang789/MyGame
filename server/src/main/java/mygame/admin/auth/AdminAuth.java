package mygame.admin.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import mygame.auth.PasswordHasher;
import mygame.auth.PasswordHasher.Hashed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 백오피스 인증/세션 관리.
 *
 * <p>학습 의도: Spring Security 의 {@code SecurityContext}/세션·CSRF 를 직접 짜본다.
 * <ul>
 *   <li>비밀번호 해시는 게임 측과 동일하게 {@link PasswordHasher} (PBKDF2) 재사용.
 *   <li>세션은 인메모리 Map. 프로세스 재시작 시 모두 무효화 — 학습 단계에서는 충분.
 *   <li>세션 토큰은 256-bit 랜덤. 쿠키는 HttpOnly + SameSite=Strict.
 * </ul>
 */
public final class AdminAuth {

    private static final Logger log = LoggerFactory.getLogger(AdminAuth.class);
    /** 세션 만료. 24h 면 운영 화면에 충분 — 강제 만료/재로그인 정책 도입 전에는 적당. */
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    public static final String COOKIE_NAME = "MYGAME_ADMIN_SID";

    public record Session(String token, long adminId, String username, String role, Instant expiresAt) {
        public boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    private final AdminAccountRepository repo;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AdminAuth(AdminAccountRepository repo) {
        this.repo = repo;
    }

    /**
     * 부트스트랩 시 admin 계정이 0 개면 환경변수 {@code MYGAME_ADMIN_BOOTSTRAP=user:password}
     * 를 읽어 1 회 시드. 운영자가 매번 수동 SQL 을 치지 않도록 하는 편의 — production
     * 단계에서는 이 분기 자체를 제거하고 별도 부트스트랩 도구를 쓰는 게 안전.
     */
    public void bootstrapIfEmpty(String bootstrapEnv) {
        if (repo.count() > 0) return;
        if (bootstrapEnv == null || bootstrapEnv.isBlank()) {
            log.warn("admin 계정이 비어있고 MYGAME_ADMIN_BOOTSTRAP 도 미설정. /admin/login 진입 불가.");
            return;
        }
        int idx = bootstrapEnv.indexOf(':');
        if (idx <= 0 || idx == bootstrapEnv.length() - 1) {
            log.error("MYGAME_ADMIN_BOOTSTRAP 형식 오류 (예: 'admin:password'). 무시.");
            return;
        }
        String username = bootstrapEnv.substring(0, idx);
        String password = bootstrapEnv.substring(idx + 1);
        Hashed h = PasswordHasher.hash(password);
        repo.create(username, h.hash(), h.salt(), "admin");
        log.info("백오피스 부트스트랩 admin 계정 생성: username={}", username);
    }

    /** 로그인 성공 시 새 세션 발급. 실패하면 {@link Optional#empty()}. */
    public Optional<Session> login(String username, String password) {
        Optional<AdminAccountRepository.AdminAccount> opt = repo.findByUsername(username);
        if (opt.isEmpty()) {
            // 동일 시간을 들이도록 더미 검증 — 사용자 존재 여부로 타이밍 차이가 새지 않게.
            PasswordHasher.verify(password, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAA");
            return Optional.empty();
        }
        AdminAccountRepository.AdminAccount a = opt.get();
        if (!PasswordHasher.verify(password, a.passwordHash(), a.salt())) return Optional.empty();
        Session s = newSession(a);
        sessions.put(s.token(), s);
        return Optional.of(s);
    }

    /** 토큰으로 세션 조회. 만료된 토큰은 자동 제거 후 empty 반환. */
    public Optional<Session> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Session s = sessions.get(token);
        if (s == null) return Optional.empty();
        if (s.isExpired(Instant.now())) {
            sessions.remove(token, s);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    private Session newSession(AdminAccountRepository.AdminAccount a) {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = B64.encodeToString(raw);
        return new Session(token, a.id(), a.username(), a.role(), Instant.now().plus(SESSION_TTL));
    }
}
