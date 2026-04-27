package mygame.db;

import java.util.Map;
import java.util.Optional;

/**
 * 플레이어 영속화 인터페이스.
 *
 * <p>Repository 패턴: 도메인 계층이 데이터 접근 구현에 의존하지 않도록
 * 추상화한다. 테스트에서는 인-메모리 구현으로 치환 가능.
 */
public interface PlayerRepository {

    /**
     * HP/MP sentinel. {@code -1} 이면 로드 시점의 effectiveStats().maxHp/maxMp 로 채운다.
     * 신규 캐릭터, DB 마이그레이션 직후 기존 행, "풀피로 시작" 의도 모두를 커버한다.
     */
    int HP_MP_RESTORE_FULL = -1;

    record PlayerData(
            long id,
            String name,
            int level,
            int exp,
            /** 소지 메소(재화). DB 기본값 0. */
            long meso,
            /** 영속화된 HP. {@link #HP_MP_RESTORE_FULL} 이면 최대치로 복원. */
            int hp,
            /** 영속화된 MP. {@link #HP_MP_RESTORE_FULL} 이면 최대치로 복원. */
            int mp,
            Map<String, Integer> items,
            /** 장비 슬롯 맵(enum 이름 → 아이템 ID). Phase I. */
            Map<String, String> equipment) {}

    Optional<PlayerData> findByName(String name);

    /** accountId 기준 조회(Phase L). 해당 계정의 캐릭터가 없으면 empty. */
    Optional<PlayerData> findByAccountId(long accountId);

    /** 이름으로 생성. 이미 있으면 예외. Phase L 이후에는 accountId 와 함께 불린다. */
    PlayerData create(String name, long accountId);

    /**
     * 레벨/EXP/메소/HP/MP/인벤토리/장비 전체를 덮어쓰기.
     *
     * <p>hp/mp 가 음수면 sentinel({@link #HP_MP_RESTORE_FULL})로 저장된다 — 다음 로드 때
     * 자동으로 최대치 복원. 호출자가 정상 값을 모를 때 사용.
     */
    void save(long id, int level, int exp, long meso, int hp, int mp,
              Map<String, Integer> items, Map<String, String> equipment);
}
