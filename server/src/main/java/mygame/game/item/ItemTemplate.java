package mygame.game.item;

import mygame.game.stat.Stats;

/**
 * 아이템 원형(정적 정의). 게임 전 기간 동일한 속성을 가진다.
 *
 * <p>record + 레지스트리 기반 디자인: 아이템 인스턴스는 템플릿 ID 만 참조하고,
 * 이름/색/효과 같은 공통 속성은 템플릿 한 벌만 메모리에 상주한다.
 *
 * <p>Phase I: EQUIPMENT 타입은 {@code slot} 과 {@code bonus} 가 non-null.
 * Phase D(인벤토리): CONSUMABLE 타입은 {@code use} 가 non-null. 생성자에서 검증.
 */
public record ItemTemplate(
        String id,
        String name,
        /** 클라이언트 색상 표시용 (0xRRGGBB). */
        int color,
        ItemType type,
        /** EQUIPMENT 타입일 때만 사용. 다른 타입은 null. */
        EquipSlot slot,
        /** EQUIPMENT 타입일 때만 사용. 장착 시 더해지는 스탯. */
        Stats bonus,
        /** CONSUMABLE 타입일 때만 사용. 사용 시 적용될 회복량. */
        UseEffect use
) {

    /**
     * 소비 아이템 사용 효과. 두 값은 독립적으로 적용된다.
     * 0 이면 해당 리소스를 회복하지 않는다.
     */
    public record UseEffect(int heal, int manaHeal) {
        public static final UseEffect NONE = new UseEffect(0, 0);
    }

    public ItemTemplate {
        if (type == ItemType.EQUIPMENT) {
            if (slot == null || bonus == null) {
                throw new IllegalArgumentException(
                        "EQUIPMENT 타입은 slot / bonus 가 필수: id=" + id);
            }
            if (use != null) {
                throw new IllegalArgumentException(
                        "EQUIPMENT 타입은 use 가 null 이어야 함: id=" + id);
            }
        } else {
            if (slot != null || bonus != null) {
                throw new IllegalArgumentException(
                        "EQUIPMENT 아닌 타입은 slot / bonus 가 null 이어야 함: id=" + id);
            }
            if (type == ItemType.CONSUMABLE && use == null) {
                throw new IllegalArgumentException(
                        "CONSUMABLE 타입은 use 가 필수: id=" + id);
            }
        }
    }

    /** 비장비·비소비(=ETC) 전용 편의 생성자. */
    public ItemTemplate(String id, String name, int color, ItemType type) {
        this(id, name, color, type, null, null, null);
        if (type == ItemType.CONSUMABLE || type == ItemType.EQUIPMENT) {
            throw new IllegalArgumentException(
                    "이 생성자는 ETC 에만 사용 가능: id=" + id + ", type=" + type);
        }
    }

    /** 장비 전용 편의 생성자. */
    public ItemTemplate(String id, String name, int color, ItemType type,
                        EquipSlot slot, Stats bonus) {
        this(id, name, color, type, slot, bonus, null);
    }

    /** 소비 전용 편의 생성자. */
    public ItemTemplate(String id, String name, int color, ItemType type, UseEffect use) {
        this(id, name, color, type, null, null, use);
    }

    public enum ItemType { CONSUMABLE, EQUIPMENT, ETC }
}
