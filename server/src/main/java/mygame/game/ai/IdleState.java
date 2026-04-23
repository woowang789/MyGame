package mygame.game.ai;

import java.util.concurrent.ThreadLocalRandom;
import mygame.game.entity.Monster;

/**
 * 정지 상태. 지정된 시간이 지나면 무작위 방향으로 Wander 상태로 전이한다.
 */
public final class IdleState implements MonsterState {

    private static final long MIN_MS = 500;
    private static final long MAX_MS = 2000;

    private long remainingMs;

    @Override
    public void onEnter(Monster m) {
        m.setVx(0);
        remainingMs = ThreadLocalRandom.current().nextLong(MIN_MS, MAX_MS);
    }

    @Override
    public void update(Monster m, long dtMs) {
        remainingMs -= dtMs;
        if (remainingMs <= 0) {
            int dir = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
            m.transitionTo(new WanderState(dir));
        }
    }
}
