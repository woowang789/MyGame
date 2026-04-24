package mygame.game.ai;

import mygame.game.entity.Monster;
import mygame.game.entity.Player;

/**
 * 특정 플레이어를 추격하는 상태.
 *
 * <p>피격 직후 CombatService 가 넉백 종료 후 상태로 지정한다. 배회 구간
 * {@code [minX, maxX]} 안에서만 움직이므로 플레이어가 구간 밖에 있으면
 * 가장자리에 멈춘다 (설계상 몬스터가 정해진 영역을 벗어나지 않게 한다).
 *
 * <p>다음 조건 중 하나라도 만족하면 Idle 로 복귀:
 * <ul>
 *   <li>어그로 지속시간 만료</li>
 *   <li>타겟 사망 또는 연결 종료</li>
 *   <li>타겟이 다른 맵으로 이동</li>
 * </ul>
 */
public final class ChaseState implements MonsterState {

    private final Player target;
    private final String originMapId;
    private final long expireAtMs;

    public ChaseState(Player target, String mapId, long durationMs) {
        this.target = target;
        this.originMapId = mapId;
        this.expireAtMs = System.currentTimeMillis() + Math.max(0, durationMs);
    }

    @Override
    public void onEnter(Monster m) {
        // 초기 방향은 target 쪽으로. update 가 곧 실제 속도를 갱신한다.
        m.setVx(target.x() < m.x() ? -m.speed() : m.speed());
    }

    @Override
    public void update(Monster m, long dtMs) {
        long now = System.currentTimeMillis();
        if (now >= expireAtMs
                || target.isDead()
                || !originMapId.equals(target.mapId())) {
            m.transitionTo(new IdleState());
            return;
        }

        double dx = target.x() - m.x();
        if (Math.abs(dx) < 2) {
            // 사실상 붙음 — 제자리에서 이후 state 에 맡긴다.
            m.setVx(0);
            return;
        }
        double vx = Math.signum(dx) * m.speed();
        double nextX = m.x() + vx * (dtMs / 1000.0);
        nextX = Math.max(m.minX(), Math.min(m.maxX(), nextX));
        m.setX(nextX);
        m.setVx(vx);
    }
}
