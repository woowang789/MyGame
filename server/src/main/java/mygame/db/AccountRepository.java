package mygame.db;

import java.util.Optional;

/**
 * 계정(로그인 주체) 영속화 인터페이스.
 *
 * <p>Phase L: 계정과 캐릭터(플레이어)는 분리된다. 한 계정에 한 캐릭터만 있는
 * 단순 모델이지만, 향후 N 캐릭터 확장을 위해 PlayerRepository 와 별도 API 로 유지한다.
 */
public interface AccountRepository {

    record Account(long id, String username, String passwordHash, String salt) {}

    Optional<Account> findByUsername(String username);

    /** 비밀번호는 호출자가 사전에 해시해서 전달한다. 저장소는 해시만 저장. */
    Account create(String username, String passwordHash, String salt);
}
