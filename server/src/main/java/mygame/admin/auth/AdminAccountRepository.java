package mygame.admin.auth;

import java.util.Optional;

/**
 * 관리자 계정 영속화 인터페이스.
 *
 * <p>게임용 {@code accounts} 테이블과 분리한다 — 관리자 권한이 게임 캐릭터 권한과
 * 결합되면 사고 위험이 커지기 때문. 권한 모델은 단순한 role 문자열만 보유.
 */
public interface AdminAccountRepository {

    record AdminAccount(long id, String username, String passwordHash, String salt, String role) {}

    Optional<AdminAccount> findByUsername(String username);

    /** 비밀번호는 호출자가 사전에 해시(PBKDF2)해서 전달. */
    AdminAccount create(String username, String passwordHash, String salt, String role);

    /** 부트스트랩 시 시드 계정이 있는지 확인하는 용도. */
    long count();
}
