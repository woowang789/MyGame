package mygame.game.shop;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mygame.game.item.ItemRegistry;
import mygame.game.shop.ShopCatalog.Entry;

/**
 * 상점 카탈로그 정적 레지스트리.
 *
 * <p>{@link ItemRegistry} / {@link mygame.game.entity.MonsterRegistry} 와 같은 패턴 — 운영
 * 단계에서는 DB 로 옮길 수 있도록 시그니처를 단순하게 유지한다. 첫 단계에서는
 * 코드 상수가 충분.
 *
 * <p>가격은 "들어오는 메소(몬스터 드롭)" 와 균형을 맞춰 설정. 잡몹 메소 드롭이
 * 1~5 수준이라 포션 50/80 정도면 의미 있는 sink 가 된다.
 */
public final class ShopRegistry {

    private static final Map<String, ShopCatalog> SHOPS;

    static {
        Map<String, ShopCatalog> m = new LinkedHashMap<>();
        m.put("henesys_general", new ShopCatalog("henesys_general", List.of(
                new Entry("red_potion", 50, 50),
                new Entry("blue_potion", 80, 50),
                new Entry("wooden_sword", 1500, 1),
                new Entry("leather_cap", 800, 1)
        )));
        SHOPS = Collections.unmodifiableMap(m);
        validate();
    }

    private ShopRegistry() {}

    /** 시작 시 1회: 카탈로그가 모르는 itemId 를 참조하지 않는지 검사 (빠른 실패). */
    private static void validate() {
        for (ShopCatalog c : SHOPS.values()) {
            for (Entry e : c.items()) {
                ItemRegistry.get(e.itemId()); // 미등록이면 즉시 IllegalArgumentException
                if (e.price() <= 0) {
                    throw new IllegalStateException(
                            "shop=" + c.shopId() + " item=" + e.itemId() + " price 는 양수여야 함");
                }
            }
        }
    }

    public static Optional<ShopCatalog> find(String shopId) {
        return Optional.ofNullable(SHOPS.get(shopId));
    }
}
