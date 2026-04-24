package mygame.auth;

import java.util.Optional;
import mygame.db.AccountRepository;
import mygame.db.AccountRepository.Account;

/**
 * 계정 등록 · 로그인 검증을 담당하는 서비스.
 *
 * <p>도메인 규칙(이름 길이, 금칙 문자 등)도 여기서 일원화한다.
 * GameServer 는 결과만 알고, 해시/DB 구현 세부는 모른다.
 */
public final class AuthService {

    /** 3~16자 영숫자/언더스코어/하이픈. 스페이스·한글은 거절. */
    private static final java.util.regex.Pattern USERNAME_REGEX =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{3,16}$");
    private static final int MIN_PASSWORD_LEN = 6;

    private final AccountRepository accountRepo;

    public AuthService(AccountRepository accountRepo) {
        this.accountRepo = accountRepo;
    }

    public sealed interface AuthResult permits AuthSuccess, AuthFailure {}
    public record AuthSuccess(Account account) implements AuthResult {}
    public record AuthFailure(String message) implements AuthResult {}

    public AuthResult register(String username, String password) {
        if (username == null || !USERNAME_REGEX.matcher(username).matches()) {
            return new AuthFailure("아이디는 3~16자 영숫자/_/- 만 허용됩니다.");
        }
        if (password == null || password.length() < MIN_PASSWORD_LEN) {
            return new AuthFailure("비밀번호는 최소 " + MIN_PASSWORD_LEN + "자 이상이어야 합니다.");
        }
        if (accountRepo.findByUsername(username).isPresent()) {
            return new AuthFailure("이미 존재하는 아이디입니다.");
        }
        PasswordHasher.Hashed h = PasswordHasher.hash(password);
        Account created = accountRepo.create(username, h.hash(), h.salt());
        return new AuthSuccess(created);
    }

    public AuthResult login(String username, String password) {
        if (username == null || password == null) {
            return new AuthFailure("아이디/비밀번호를 입력하세요.");
        }
        Optional<Account> found = accountRepo.findByUsername(username);
        // 계정이 없어도 해시 검증을 수행해 타이밍으로 아이디 존재 여부를 노출하지 않는다.
        if (found.isEmpty()) {
            PasswordHasher.verify(password, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAA");
            return new AuthFailure("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        Account a = found.get();
        if (!PasswordHasher.verify(password, a.passwordHash(), a.salt())) {
            return new AuthFailure("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        return new AuthSuccess(a);
    }
}
