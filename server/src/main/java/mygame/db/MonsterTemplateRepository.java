package mygame.db;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import mygame.game.entity.MonsterTemplate;
import mygame.game.item.DropTable;

/**
 * 몬스터 템플릿 영속화. drop table 은 1:N 정규화(monster_drops) — Repository 가
 * 두 테이블 join 결과를 도메인 record 한 벌로 합쳐서 노출한다.
 */
public interface MonsterTemplateRepository {

    Optional<MonsterTemplate> findById(String monsterId);

    List<MonsterTemplate> findAll();

    /** 캐시 부트스트랩 시 한 번에 적재. id → template. */
    Map<String, MonsterTemplate> loadAll();

    /**
     * 템플릿(부모 행) 추가/수정. drop table 은 별도 메서드로 갱신 — 트랜잭션 분리는
     * 학습 단계에서 단순화. 운영 화면에서 두 호출을 묶어 쓴다.
     *
     * @return 영향받은 행 수
     */
    int upsertTemplate(MonsterTemplate t);

    /** 자식(monster_drops) 전체를 새 drop table 로 교체 — 행 단위 add/remove 보다 단순. */
    int replaceDrops(String monsterId, DropTable drops);

    /** 단일 drop 라인 추가/수정 — 운영 UI 의 인라인 편집용. */
    int upsertDropLine(String monsterId, String itemId, double chance, int sortOrder);

    /** 단일 drop 라인 삭제. */
    int deleteDropLine(String monsterId, String itemId);

    /** 몬스터 자체 삭제. monster_drops 는 FK CASCADE 로 함께 삭제. */
    int deleteById(String monsterId);

    /** 무결성 검사용: 본 monsterId 를 SpawnPoint 에서 사용 중인 등 외부 의존은 World 에 있다.
     *  현재는 World 의 정적 SpawnPoint 가 hardcoded 라 검사 대상 외 — 향후 spawn 도 DB 화 시 추가. */
}
