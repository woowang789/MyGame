package mygame.game.item;

/**
 * 장비 슬롯 종류. 한 플레이어는 각 슬롯에 최대 1점씩 장착 가능.
 *
 * <p>Phase I 에서는 3슬롯으로 시작했고, 이후 GLOVES / SHOES 를 추가했다.
 * 슬롯을 늘려도 Decorator 체인 길이만 한 칸 늘어날 뿐, Player · 데미지 계산
 * 코드는 손대지 않아도 된다(개방-폐쇄 원칙 검증).
 */
public enum EquipSlot {
    WEAPON,
    HAT,
    ARMOR,
    GLOVES,
    SHOES
}
