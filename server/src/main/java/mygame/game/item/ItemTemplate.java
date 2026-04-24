package mygame.game.item;

import mygame.game.stat.Stats;

/**
 * 아이템 원형(정적 정의). 게임 전 기간 동일한 속성을 가진다.
 *
 * <p>record + 레지스트리 기반 디자인: 아이템 인스턴스는 템플릿 ID 만 참조하고,
 * 이름/색/효과 같은 공통 속성은 템플릿 한 벌만 메모리에 상주한다.
 *
 * <p>Phase I: EQUIPMENT 타입은 {@code slot} 과 {@code bonus} 가 non-null 이고,
 * 나머지 타입은 둘 다 null 이다. 생성자에서 검증.
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
        Stats bonus
) {

    public ItemTemplate {
        if (type == ItemType.EQUIPMENT) {
            if (slot == null || bonus == null) {
                throw new IllegalArgumentException(
                        "EQUIPMENT 타입은 slot / bonus 가 필수: id=" + id);
            }
        } else {
            if (slot != null || bonus != null) {
                throw new IllegalArgumentException(
                        "EQUIPMENT 아닌 타입은 slot / bonus 가 null 이어야 함: id=" + id);
            }
        }
    }

    /** 비장비 아이템 생성 편의 생성자. */
    public ItemTemplate(String id, String name, int color, ItemType type) {
        this(id, name, color, type, null, null);
    }

    public enum ItemType { CONSUMABLE, EQUIPMENT, ETC }
}
