package mygame.game.item;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mygame.game.item.ItemTemplate.ItemType;
import mygame.game.stat.Stats;

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
        // Phase I: 장비 아이템. bonus 는 장착 시 BaseStats 에 더해지는 값.
        // 순서: (maxHp, maxMp, attack, speed)
        put(m, new ItemTemplate("wooden_sword", "나무 검", 0x8b5a2b,
                ItemType.EQUIPMENT, EquipSlot.WEAPON, new Stats(0, 0, 10, 0)));
        put(m, new ItemTemplate("iron_sword", "철 검", 0xbfc7d5,
                ItemType.EQUIPMENT, EquipSlot.WEAPON, new Stats(0, 0, 25, 0)));
        put(m, new ItemTemplate("leather_cap", "가죽 모자", 0x6a4e2a,
                ItemType.EQUIPMENT, EquipSlot.HAT, new Stats(15, 5, 0, 0)));
        put(m, new ItemTemplate("cloth_armor", "천 갑옷", 0xcfa16a,
                ItemType.EQUIPMENT, EquipSlot.ARMOR, new Stats(25, 0, 0, 0)));
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

    /** 주어진 아이템이 장비(EQUIPMENT)인지 검사. null-safe. */
    public static boolean isEquipment(String id) {
        ItemTemplate t = TEMPLATES.get(id);
        return t != null && t.type() == ItemType.EQUIPMENT;
    }
}
