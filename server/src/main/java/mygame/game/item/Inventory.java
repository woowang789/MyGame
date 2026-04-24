package mygame.game.item;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 플레이어 인벤토리. 템플릿 ID 별 수량 맵으로 유지하며
 * 동일 아이템은 스택된다. (구현 단순화를 위해 슬롯 개념은 생략)
 *
 * <p>동시성: 현재 한 플레이어의 WebSocket 스레드가 주로 접근하지만,
 * 방어적으로 {@code synchronized} 메서드로 보호한다.
 */
public final class Inventory {

    private final Map<String, Integer> counts = new LinkedHashMap<>();

    public synchronized void add(String templateId, int amount) {
        if (amount <= 0) return;
        counts.merge(templateId, amount, Integer::sum);
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

    public synchronized Map<String, Integer> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }
}
