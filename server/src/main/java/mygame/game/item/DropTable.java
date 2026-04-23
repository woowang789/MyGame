package mygame.game.item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 몬스터 처치 시 드롭 확률 테이블.
 *
 * <p>각 엔트리는 독립 시행(Bernoulli trial)으로 처리된다. 결과 드롭 개수가
 * 0 개일 수도, 여러 개일 수도 있다. 메이플의 드롭 방식과 유사.
 */
public final class DropTable {

    public record Entry(String itemId, double chance) {}

    private final List<Entry> entries;

    public DropTable(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** 각 엔트리에 대해 확률 롤을 돌려 당첨된 itemId 리스트 반환. */
    public List<String> roll() {
        if (entries.isEmpty()) return Collections.emptyList();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<String> drops = new ArrayList<>();
        for (Entry e : entries) {
            if (rng.nextDouble() < e.chance()) drops.add(e.itemId());
        }
        return drops;
    }

    public static DropTable of(Entry... entries) {
        return new DropTable(List.of(entries));
    }
}
