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

    record PlayerData(
            long id,
            String name,
            int level,
            int exp,
            Map<String, Integer> items,
            /** 장비 슬롯 맵(enum 이름 → 아이템 ID). Phase I. */
            Map<String, String> equipment) {}

    Optional<PlayerData> findByName(String name);

    /** accountId 기준 조회(Phase L). 해당 계정의 캐릭터가 없으면 empty. */
    Optional<PlayerData> findByAccountId(long accountId);

    /** 이름으로 생성. 이미 있으면 예외. Phase L 이후에는 accountId 와 함께 불린다. */
    PlayerData create(String name, long accountId);

    /** 레벨/EXP/인벤토리/장비 전체를 덮어쓰기. */
    void save(long id, int level, int exp, Map<String, Integer> items, Map<String, String> equipment);
}
