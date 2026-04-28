package mygame.game.item;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mygame.game.item.ItemTemplate.ItemType;
import mygame.game.item.ItemTemplate.UseEffect;
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
        // 매입가 정책:
        //  - NPC 가 파는 장비: 구매가의 50%
        //  - 드롭 전용 장비: 임의 책정 (강화 시스템 도입 시 재조정 예정)
        //  - 잡템: 메소 드롭(1~5)과 비슷한 수준 — 인플레이션 가속 방지
        //  - 포션: 사용자 결정으로 매입 가능
        Map<String, ItemTemplate> m = new LinkedHashMap<>();
        put(m, new ItemTemplate("red_potion", "빨간 포션", 0xe74c3c,
                ItemType.CONSUMABLE, new UseEffect(30, 0), 25L));
        put(m, new ItemTemplate("blue_potion", "파란 포션", 0x3498db,
                ItemType.CONSUMABLE, new UseEffect(0, 30), 40L));
        put(m, new ItemTemplate("snail_shell", "달팽이 껍질", 0xb36836,
                ItemType.ETC, 5L));
        // Phase I: 장비 아이템. bonus 는 장착 시 BaseStats 에 더해지는 값.
        // 순서: (maxHp, maxMp, attack, speed)
        put(m, new ItemTemplate("wooden_sword", "나무 검", 0x8b5a2b,
                ItemType.EQUIPMENT, EquipSlot.WEAPON, new Stats(0, 0, 10, 0), 750L));
        put(m, new ItemTemplate("iron_sword", "철 검", 0xbfc7d5,
                ItemType.EQUIPMENT, EquipSlot.WEAPON, new Stats(0, 0, 25, 0), 1000L));
        put(m, new ItemTemplate("leather_cap", "가죽 모자", 0x6a4e2a,
                ItemType.EQUIPMENT, EquipSlot.HAT, new Stats(15, 5, 0, 0), 400L));
        put(m, new ItemTemplate("cloth_armor", "천 갑옷", 0xcfa16a,
                ItemType.EQUIPMENT, EquipSlot.ARMOR, new Stats(25, 0, 0, 0), 800L));
        // 새 슬롯 추가 검증용. 게임 로직(Player·CombatService) 은 변경 없이 동작해야 한다.
        put(m, new ItemTemplate("work_gloves", "작업 장갑", 0x7d5b3a,
                ItemType.EQUIPMENT, EquipSlot.GLOVES, new Stats(0, 0, 4, 0), 200L));
        put(m, new ItemTemplate("running_shoes", "달리기 신발", 0x4caf50,
                ItemType.EQUIPMENT, EquipSlot.SHOES, new Stats(0, 0, 0, 30), 200L));
        TEMPLATES = Collections.unmodifiableMap(m);
    }

    /** 모든 매입가 맵 (sellPrice > 0 인 아이템만). META 패킷으로 클라에 전달. */
    public static java.util.Map<String, Long> sellPrices() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (ItemTemplate t : TEMPLATES.values()) {
            if (t.isSellable()) out.put(t.id(), t.sellPrice());
        }
        return java.util.Collections.unmodifiableMap(out);
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

    /** 장비로 등록된 모든 아이템 ID. 클라 HUD 에 SSoT 로 전달. */
    public static java.util.List<String> equipmentIds() {
        return TEMPLATES.values().stream()
                .filter(t -> t.type() == ItemType.EQUIPMENT)
                .map(ItemTemplate::id)
                .toList();
    }
}
