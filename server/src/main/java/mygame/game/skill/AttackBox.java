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
}
