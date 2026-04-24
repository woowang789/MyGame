package mygame.game.item;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mygame.game.item.ItemTemplate.ItemType;

/**
 * 플레이어 인벤토리. 템플릿 ID 별 수량 맵으로 유지하며
 * 동일 아이템은 스택된다. (구현 단순화를 위해 슬롯 개념은 생략)
 *
 * <p>Phase D: 타입별 distinct id 상한을 적용한다. 같은 아이템이 스택으로 쌓이는
 * 것은 제한 없지만, 서로 다른 종류의 아이템이 한 탭의 슬롯 수를 넘지 못하도록
 * {@link #MAX_DISTINCT_PER_TYPE} 로 거절한다. 클라의 24 슬롯 그리드와 동기.
 *
 * <p>동시성: 현재 한 플레이어의 WebSocket 스레드가 주로 접근하지만,
 * 방어적으로 {@code synchronized} 메서드로 보호한다.
 */
public final class Inventory {

    /** 타입(탭)별로 담을 수 있는 서로 다른 아이템 종류의 최대 개수. 클라 그리드와 1:1. */
    public static final int MAX_DISTINCT_PER_TYPE = 24;

    private final Map<String, Integer> counts = new LinkedHashMap<>();

    /**
     * 아이템 수량을 더한다.
     *
     * @return 성공 여부. 기존에 없는 템플릿을 추가하려 할 때 해당 타입의 distinct
     *   상한을 넘게 되면 {@code false} 를 반환(변경 없음). 이미 있는 아이템의
     *   스택 증가는 항상 성공한다.
     */
    public synchronized boolean add(String templateId, int amount) {
        if (amount <= 0) return true;
        if (!counts.containsKey(templateId)) {
            ItemType type = ItemRegistry.get(templateId).type();
            int distinct = distinctCountOfType(type);
            if (distinct >= MAX_DISTINCT_PER_TYPE) return false;
        }
        counts.merge(templateId, amount, Integer::sum);
        return true;
    }

    /** 지정 타입의 distinct 아이템 개수. 용량 체크 전용. */
    private int distinctCountOfType(ItemType type) {
        int n = 0;
        for (String id : counts.keySet()) {
            if (ItemRegistry.get(id).type() == type) n++;
        }
        return n;
    }

    /**
     * 지정 수량을 차감. 보유량이 부족하면 {@code false} 반환(변경 없음).
     * 0 이 되면 맵에서 엔트리 자체를 제거해 UI 에 빈 줄이 남지 않게 한다.
     */
    public synchronized boolean remove(String templateId, int amount) {
        if (amount <= 0) return true;
        Integer cur = counts.get(templateId);
        if (cur == null || cur < amount) return false;
        int next = cur - amount;
        if (next == 0) counts.remove(templateId);
        else counts.put(templateId, next);
        return true;
    }

    public synchronized boolean has(String templateId) {
        Integer cur = counts.get(templateId);
        return cur != null && cur > 0;
    }

    public synchronized int countOf(String templateId) {
        return counts.getOrDefault(templateId, 0);
    }

    public synchronized Map<String, Integer> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }
}
