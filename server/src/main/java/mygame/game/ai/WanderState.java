package mygame.game.ai;

import java.util.concurrent.ThreadLocalRandom;
import mygame.game.entity.Monster;

/**
 * 정해진 방향으로 이동하는 상태. 경계에 닿거나 제한 시간이 다하면 Idle 로 복귀.
 */
public final class WanderState implements MonsterState {

    private static final long MIN_MS = 1000;
    private static final long MAX_MS = 3000;

    private final int dir;
    private long remainingMs;

    public WanderState(int dir) {
        this.dir = dir;
    }

    @Override
    public void onEnter(Monster m) {
        m.setVx(dir * m.speed());
        remainingMs = ThreadLocalRandom.current().nextLong(MIN_MS, MAX_MS);
    }

    @Override
    public void update(Monster m, long dtMs) {
        remainingMs -= dtMs;
        double nextX = m.x() + m.vx() * (dtMs / 1000.0);

        if (nextX <= m.minX() || nextX >= m.maxX() || remainingMs <= 0) {
            m.setX(Math.max(m.minX(), Math.min(m.maxX(), nextX)));
            m.transitionTo(new IdleState());
            return;
        }
        m.setX(nextX);
    }
}
