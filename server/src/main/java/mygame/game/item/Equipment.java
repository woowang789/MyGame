package mygame.game.item;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import mygame.game.stat.EquipmentStatDecorator;
import mygame.game.stat.StatProvider;

/**
 * 플레이어가 착용 중인 장비 모음.
 *
 * <p>학습 포인트(Decorator):
 * {@link #decorate(StatProvider)} 가 base 위에 각 장비 보너스를 얇게 한 겹씩 감싸
 * {@link StatProvider} 체인을 만든다. 슬롯 수가 늘어도 호출자(플레이어·데미지 계산)는
 * 항상 {@code stats()} 한 줄만 쓰면 된다.
 *
 * <p>동시성: 착용/해제는 한 플레이어의 WebSocket 스레드가 주로 호출하지만
 * 스냅샷 조회는 다른 스레드(게임 루프)에서 올 수 있어 {@code synchronized} 로 보호.
 */
public final class Equipment {

    private final Map<EquipSlot, String> slots = new EnumMap<>(EquipSlot.class);

    /**
     * 아이템을 해당 슬롯에 장착. 기존 장비가 있다면 그 템플릿 ID 를 반환(호출자가 인벤토리로 되돌려 줌).
     */
    public synchronized String equip(String templateId) {
        ItemTemplate t = ItemRegistry.get(templateId);
        if (t.type() != ItemTemplate.ItemType.EQUIPMENT) {
            throw new IllegalArgumentException("장비 아이템이 아님: " + templateId);
        }
        return slots.put(t.slot(), templateId);
    }

    /** 슬롯 장비 해제. 비어 있으면 null. */
    public synchronized String unequip(EquipSlot slot) {
        return slots.remove(slot);
    }

    public synchronized Map<EquipSlot, String> snapshot() {
        return Collections.unmodifiableMap(new EnumMap<>(slots));
    }

    /**
     * base 위에 현재 장비들의 보너스를 감싸 Decorator 체인을 생성.
     * 호출자는 결과 StatProvider 의 {@code stats()} 만 쓰면 된다.
     */
    public synchronized StatProvider decorate(StatProvider base) {
        StatProvider chain = base;
        for (String id : slots.values()) {
            ItemTemplate t = ItemRegistry.get(id);
            chain = new EquipmentStatDecorator(chain, t.bonus());
        }
        return chain;
    }
}
