package mygame.game.item;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mygame.game.item.ItemTemplate.ItemType;

/**
 * 아이템 원형 레지스트리.
 *
 * <p>Factory 패턴의 변종: 생성 로직은 단순한 조회이지만,
 * "템플릿 ID → 인스턴스" 해석을 한 곳으로 집중시켜 호출자가
 * 템플릿 구현 세부를 몰라도 되도록 한다. 추후 DB/JSON 로드로
 * 교체해도 시그니처가 유지된다.
 */
public final class ItemRegistry {

    private static final Map<String, ItemTemplate> TEMPLATES;

    static {
        Map<String, ItemTemplate> m = new LinkedHashMap<>();
        put(m, new ItemTemplate("red_potion", "빨간 포션", 0xe74c3c, ItemType.CONSUMABLE));
        put(m, new ItemTemplate("blue_potion", "파란 포션", 0x3498db, ItemType.CONSUMABLE));
        put(m, new ItemTemplate("snail_shell", "달팽이 껍질", 0xb36836, ItemType.ETC));
        TEMPLATES = Collections.unmodifiableMap(m);
    }

    private ItemRegistry() {}

    private static void put(Map<String, ItemTemplate> m, ItemTemplate t) {
        m.put(t.id(), t);
    }

    public static ItemTemplate get(String id) {
        ItemTemplate t = TEMPLATES.get(id);
        if (t == null) {
            throw new IllegalArgumentException("알 수 없는 아이템: " + id);
        }
        return t;
    }
}
