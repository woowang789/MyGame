package mygame.db;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import mygame.game.shop.ShopCatalog;

/**
 * 상점 카탈로그 영속화 인터페이스.
 *
 * <p>도메인의 {@link ShopCatalog} 를 그대로 노출 — 운영 화면과 게임 로직이 같은 모델을 본다.
 * 가변 mutate API({@code upsertItem} 등) 는 즉시 commit 이며, 호출자(=admin 명령) 가
 * 응답 후 {@code ShopRegistry.reload} 를 트리거해 인메모리 캐시를 갱신할 책임이 있다.
 */
public interface ShopRepository {

    /** 운영 목록 화면용 요약. itemCount 는 admin 화면의 한 줄 표시에 충분. */
    record ShopSummary(String id, String name, int itemCount) {}

    Optional<ShopCatalog> findById(String shopId);

    Optional<ShopSummary> findSummary(String shopId);

    List<ShopSummary> findAllSummaries();

    /** 캐시 부트스트랩 시 1회 호출. 모든 카탈로그를 한 번에 적재. */
    Map<String, ShopCatalog> loadAllCatalogs();

    /**
     * 상점 자체(메타) 삽입/이름 갱신. {@code shop_items} 가 FK 로 참조하므로 시드용.
     * @return 영향받은 행 수
     */
    int upsertShop(String shopId, String name);

    /**
     * 한 아이템 라인 추가/수정. price/stockPerTx/sortOrder 모두 갱신.
     * @return 영향받은 행 수
     */
    int upsertItem(String shopId, String itemId, long price, int stockPerTx, int sortOrder);

    /** 한 아이템 라인 삭제. @return 영향받은 행 수 (없으면 0) */
    int deleteItem(String shopId, String itemId);
}
