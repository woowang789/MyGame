package mygame.game.item;

/**
 * 아이템 원형(정적 정의). 게임 전 기간 동일한 속성을 가진다.
 *
 * <p>record + 레지스트리 기반 디자인: 아이템 인스턴스는 템플릿 ID 만 참조하고,
 * 이름/색/효과 같은 공통 속성은 템플릿 한 벌만 메모리에 상주한다.
 */
public record ItemTemplate(
        String id,
        String name,
        /** 클라이언트 색상 표시용 (0xRRGGBB). */
        int color,
        ItemType type
) {

    public enum ItemType { CONSUMABLE, EQUIPMENT, ETC }
}
