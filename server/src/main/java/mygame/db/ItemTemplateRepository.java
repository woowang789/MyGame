package mygame.db;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import mygame.game.item.ItemTemplate;

/**
 * 아이템 템플릿 영속화 인터페이스.
 *
 * <p>{@link ItemTemplate} 의 type 별 nullable 필드(slot/bonus/use)는 DB 컬럼에 평탄화해
 * 저장한다. 매핑 책임은 구현체({@link JdbcItemTemplateRepository}) 가 진다 — type 에 따라
 * convenience ctor 를 골라 invariants 를 도메인 record 가 직접 지키게 한다.
 */
public interface ItemTemplateRepository {

    Optional<ItemTemplate> findById(String itemId);

    /** 부트스트랩·관리자 목록 화면용. id ASC 정렬. */
    List<ItemTemplate> findAll();

    /** 캐시 부트스트랩 시 한 번에 적재. id → template. */
    Map<String, ItemTemplate> loadAll();

    /**
     * 추가/수정. 도메인 invariants 위반 시 호출자가 사전에 거른다 — 본 메서드는
     * 단순 영속화에 집중. type 별로 의미 있는 컬럼만 채우고 나머지는 0/NULL.
     *
     * @return 영향받은 행 수
     */
    int upsert(ItemTemplate template);

    int deleteById(String itemId);

    /**
     * 무결성 검사용: 본 itemId 를 참조하는 shop_items 행 수.
     * 0 보다 크면 admin 측에서 아이템 삭제를 거절해야 한다 — DB FK 가 없는 자리에서
     * 의미 무결성을 명시적 쿼리로 지킨다.
     */
    int countShopReferences(String itemId);
}
