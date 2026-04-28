package mygame.game.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import mygame.game.ai.ChaseState;
import mygame.game.ai.IdleState;
import mygame.game.ai.MonsterState;
import mygame.game.ai.WanderState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 넉백 종료 후 상태 복귀 정책 검증 (옵션 B: 직전 상태 복귀).
 *
 * <p>우선순위: 명시 후속(post) > 직전 상태(pre) > Idle 안전망.
 */
class MonsterKnockbackTest {

    private Monster m;

    @BeforeEach
    void setUp() {
        m = new Monster(1, "test", 0, 0, -100, 100, 50, 100, 5, 1000);
    }

    /** 시간 종속 분기를 짧게 통과시키기 위한 sleep + update 호출 헬퍼. */
    private void waitKnockbackEnd() {
        try { Thread.sleep(3); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        m.update(16);
    }

    @Test
    @DisplayName("postKnockbackState 가 지정되면 그쪽으로 전이한다(기존 동작)")
    void postState_takesPriority() {
        m.transitionTo(new WanderState(1));
        m.applyKnockback(50, 1);
        ChaseState chase = new ChaseState(stubPlayer(), "test", 4000);
        m.setPostKnockbackState(chase);

        waitKnockbackEnd();

        assertSame(chase, m.state(), "명시 후속 상태가 우선");
    }

    @Test
    @DisplayName("postKnockbackState 가 없으면 직전 상태로 복귀한다(옵션 B 핵심)")
    void preState_resumesWhenPostMissing() {
        WanderState wander = new WanderState(1);
        m.transitionTo(wander);
        m.applyKnockback(50, 1);
        // postKnockbackState 미지정

        waitKnockbackEnd();

        // 같은 인스턴스로 복귀하므로 onEnter 가 다시 호출되어 vx/remaining 도 fresh
        assertSame(wander, m.state(), "직전 Wander 인스턴스로 복귀");
    }

    @Test
    @DisplayName("연속 피격: 두 번째 applyKnockback 이 직전 상태를 덮어쓰지 않는다")
    void preState_notOverwrittenByChainedKnockback() {
        WanderState wander = new WanderState(1);
        m.transitionTo(wander);

        m.applyKnockback(50, 50);   // 첫 넉백 — pre = Wander
        m.applyKnockback(80, 1);    // 사슬 — pre 갱신되면 안 됨

        waitKnockbackEnd();

        assertSame(wander, m.state(), "첫 넉백 직전 Wander 가 그대로 보존돼야 함");
    }

    @Test
    @DisplayName("post · pre 모두 없으면 Idle 로 fallback")
    void idleFallback_whenBothMissing() {
        // state 가 null 인 갓 만든 몬스터에 곧장 넉백을 거는 인위적 케이스
        m.applyKnockback(50, 1);

        waitKnockbackEnd();

        MonsterState s = m.state();
        assertNotNull(s);
        assertEquals(IdleState.class, s.getClass(), "최종 안전망은 Idle");
    }

    /**
     * ChaseState 가 요구하는 최소한의 Player stub.
     *
     * <p>주의: Player 의 hp 기본값은 0 이라 그대로 두면 {@link Player#isDead()} 가
     * true 가 되어 ChaseState 가 즉시 Idle 로 전이해버린다. 테스트 의도와 무관한
     * 부작용이므로 fullHealHp 로 살려 둔다.
     */
    private static Player stubPlayer() {
        Player p = new Player(99, "stub", null, "test", 0, 0);
        p.fullHealHp();
        return p;
    }
}
