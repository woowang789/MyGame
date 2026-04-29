package mygame.game.shop;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import mygame.db.ShopRepository;
import mygame.game.item.ItemRegistry;
import mygame.game.shop.ShopCatalog.Entry;

/**
 * 상점 카탈로그 인메모리 캐시 + 핫 리로드 진입점.
 *
 * <p>이전 단계에서는 정적 코드 상수가 SSoT 였다. Phase 4 에서 DB 의 {@code shops}/
 * {@code shop_items} 가 SSoT 가 되고, 본 클래스는 다음 두 가지를 한다:
 * <ul>
 *   <li>서버 시작 시 {@link #bootstrap(ShopRepository)} 가 모든 카탈로그를 한 번 적재.
 *   <li>운영 화면이 가격/재고를 변경한 직후 {@link #reload(String)} 로 해당 shopId 만 갱신.
 * </ul>
 *
 * <p>{@link ShopService} 와 {@link mygame.network.ShopHandler} 는 기존과 동일하게
 * {@link #find(String)} 만 호출 — 도메인 코드 변경 없이 데이터 출처가 바뀐다.
 *
 * <p>학습 메모: "정적 메서드 파사드 뒤에 가변 캐시" 패턴은 게임 핫패스에서 트랜잭션마다
 * DB 를 때리지 않고도 운영 갱신을 즉시 반영할 수 있게 해 준다. 멀티 인스턴스로 확장하면
 * 노드 간 캐시 무효화가 추가로 필요(현재 학습 단계에서는 단일 JVM 가정).
 */
public final class ShopRegistry {

    private static volatile ShopRepository repo = null;
    private static final ConcurrentHashMap<String, ShopCatalog> CACHE = new ConcurrentHashMap<>();

    private ShopRegistry() {}

    /**
     * 시작 시 1회 호출. 모든 카탈로그를 적재하고 일관성 검증을 수행한다 — 운영자가
     * 잘못된 itemId 로 행을 넣어 두면 여기서 즉시 빠르게 실패한다.
     */
    public static synchronized void bootstrap(ShopRepository repository) {
        repo = repository;
        Map<String, ShopCatalog> all = repository.loadAllCatalogs();
        CACHE.clear();
        CACHE.putAll(all);
        validateAll();
    }

    public static Optional<ShopCatalog> find(String shopId) {
        return Optional.ofNullable(CACHE.get(shopId));
    }

    /**
     * 단일 shop 의 캐시를 DB 기준으로 다시 적재. admin 명령이 mutation 직후 호출.
     * shop 자체가 삭제됐으면 캐시에서도 제거.
     */
    public static void reload(String shopId) {
        if (repo == null) return;
        Optional<ShopCatalog> fresh = repo.findById(shopId);
        if (fresh.isPresent()) {
            CACHE.put(shopId, fresh.get());
        } else {
            CACHE.remove(shopId);
        }
    }

    /**
     * 부트스트랩 시 카탈로그가 미등록 itemId 를 참조하지 않는지 + 가격이 양수인지 점검.
     * ItemRegistry 가 미등록 itemId 를 만나면 IllegalArgumentException 을 던진다.
     */
    private static void validateAll() {
        for (ShopCatalog c : CACHE.values()) {
            for (Entry e : c.items()) {
                ItemRegistry.get(e.itemId());
                if (e.price() <= 0) {
                    throw new IllegalStateException(
                            "shop=" + c.shopId() + " item=" + e.itemId()
                                    + " price 는 양수여야 함");
                }
            }
        }
    }
}
