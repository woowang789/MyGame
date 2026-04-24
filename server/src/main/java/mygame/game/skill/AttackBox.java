package mygame.game.skill;

import java.util.ArrayList;
import java.util.List;
import mygame.game.GameMap;
import mygame.game.entity.Monster;
import mygame.game.entity.Player;

/**
 * 근접 공격 판정 박스 계산.
 *
 * <p>여러 스킬이 같은 "플레이어 앞 사각형" 판정을 쓰므로 한 곳에 모았다.
 * 사거리 배수만 스킬마다 다르게 주입.
 */
public final class AttackBox {

    private static final double BASE_RANGE_X = 70;
    private static final double RANGE_Y_UP = 60;
    private static final double RANGE_Y_DOWN = 60;

    private AttackBox() {}

    /** 플레이어의 facing 기준 사각형 안에 들어온 살아있는 몬스터 리스트 반환. */
    public static List<Monster> monstersInFront(Player caster, GameMap map, String dir, double rangeMul) {
        double px = caster.x();
        double py = caster.y();
        double rangeX = BASE_RANGE_X * rangeMul;
        double hitMinX = "left".equals(dir) ? px - rangeX : px;
        double hitMaxX = "left".equals(dir) ? px : px + rangeX;
        double hitMinY = py - RANGE_Y_UP;
        double hitMaxY = py + RANGE_Y_DOWN;

        List<Monster> result = new ArrayList<>();
        for (Monster m : map.monsters()) {
            if (m.isDead()) continue;
            if (m.x() < hitMinX || m.x() > hitMaxX) continue;
            if (m.y() < hitMinY || m.y() > hitMaxY) continue;
            result.add(m);
        }
        return result;
    }

    /**
     * 판정 박스 안에서 시전자와 x 거리가 가까운 순으로 최대 {@code maxTargets} 마리 반환.
     * 기본 공격·단일/제한 타격 스킬이 공유하는 "가장 가까운 N마리 고르기" 로직을 모아
     * 호출부마다 정렬 코드를 중복하지 않게 한다.
     */
    public static List<Monster> nearestInFront(Player caster, GameMap map, String dir,
                                               double rangeMul, int maxTargets) {
        if (maxTargets <= 0) return List.of();
        List<Monster> inBox = monstersInFront(caster, map, dir, rangeMul);
        if (inBox.size() <= maxTargets && maxTargets >= inBox.size()) {
            // 이미 상한 이하라도 거리 순 정렬은 유지해 "가까운 것부터" 의미를 보존.
        }
        double cx = caster.x();
        inBox.sort((a, b) -> Double.compare(Math.abs(a.x() - cx), Math.abs(b.x() - cx)));
        return inBox.size() > maxTargets ? inBox.subList(0, maxTargets) : inBox;
    }
}
