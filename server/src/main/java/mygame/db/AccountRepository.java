package mygame.db;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 계정(로그인 주체) 영속화 인터페이스.
 *
 * <p>Phase L: 계정과 캐릭터(플레이어)는 분리된다. 한 계정에 한 캐릭터만 있는
 * 단순 모델이지만, 향후 N 캐릭터 확장을 위해 PlayerRepository 와 별도 API 로 유지한다.
 */
public interface AccountRepository {

    /** 인증 흐름에서 사용. 정지(disabled) 여부도 같이 실어 AuthService 가 검사. */
    record Account(long id, String username, String passwordHash, String salt, boolean disabled) {}

    /** 백오피스 목록용 요약 — 비밀번호 해시·솔트는 절대 노출하지 않음. */
    record AccountSummary(long id, String username, Instant createdAt, boolean disabled) {}

    Optional<Account> findByUsername(String username);

    /** id 기준 조회. 백오피스 상세 페이지 등에서 사용. */
    Optional<AccountSummary> findById(long accountId);

    /** 비밀번호는 호출자가 사전에 해시해서 전달한다. 저장소는 해시만 저장. */
    Account create(String username, String passwordHash, String salt);

    /** 백오피스 페이지네이션. id ASC 정렬. limit 은 양수 가정. */
    List<AccountSummary> findPage(int offset, int limit);

    long count();

    /**
     * 정지/해제 토글.
     *
     * @return 변경된 행 수 (id 가 존재하지 않으면 0)
     */
    int setDisabled(long accountId, boolean disabled);

    /**
     * 비밀번호 해시·솔트 갱신. 호출자가 사전에 해시한 값을 전달한다.
     *
     * @return 변경된 행 수 (id 가 존재하지 않으면 0)
     */
    int updatePassword(long accountId, String passwordHash, String salt);
}
