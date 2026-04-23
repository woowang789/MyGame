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

    public synchronized Map<String, Integer> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }
}
