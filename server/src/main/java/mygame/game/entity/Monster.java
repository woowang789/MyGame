package mygame.game.entity;

import mygame.game.ai.MonsterState;

/**
 * 서버에서 권위적으로 움직이는 몬스터 엔티티.
 *
 * <p>위치/속도는 서버의 게임 루프가 갱신하고 클라이언트는 보간으로 따라간다.
 * 상태 전이는 {@link MonsterState} 전략 객체가 담당한다 (State 패턴).
 */
public final class Monster {

    private final int id;
    private final String template;
    private final double minX;
    private final double maxX;
    private final double groundY;
    private final double speed;

    private final int maxHp;
    private final int attackDamage;
    private final long attackIntervalMs;
    private double x;
    private double vx;
    private int hp;
    private MonsterState state;
    /** 넉백 종료 시각(ms, System.currentTimeMillis). 이 값 이전이면 state 대신 넉백 속도로 이동. */
    private long knockbackUntilMs = 0;
    private double knockbackVx = 0;
    /** 넉백 종료 직후 전이할 상태. null 이면 Idle 로 복귀. 어그로(추격) 삽입 지점. */
    private MonsterState postKnockbackState = null;

    public Monster(int id, String template,
                   double spawnX, double groundY,
                   double minX, double maxX, double speed,
                   int maxHp, int attackDamage, long attackIntervalMs) {
        this.id = id;
        this.template = template;
        this.x = spawnX;
        this.groundY = groundY;
        this.minX = minX;
        this.maxX = maxX;
        this.speed = speed;
        this.maxHp = maxHp;
        this.hp = maxHp;
        this.attackDamage = attackDamage;
        this.attackIntervalMs = attackIntervalMs;
    }

    public int id() { return id; }
    public String template() { return template; }
    public double x() { return x; }
    public double y() { return groundY; }
    public double vx() { return vx; }
    public double minX() { return minX; }
    public double maxX() { return maxX; }
    public double speed() { return speed; }
    public MonsterState state() { return state; }
    public int hp() { return hp; }
    public int maxHp() { return maxHp; }
    public int attackDamage() { return attackDamage; }
    public long attackIntervalMs() { return attackIntervalMs; }
    public boolean isDead() { return hp <= 0; }

    public void setX(double x) { this.x = x; }
    public void setVx(double vx) { this.vx = vx; }

    /** 데미지 적용. 사망 여부는 {@link #isDead()} 로 확인. */
    public int applyDamage(int dmg) {
        int applied = Math.max(0, Math.min(hp, dmg));
        hp -= applied;
        return applied;
    }

    public void transitionTo(MonsterState next) {
        this.state = next;
        next.onEnter(this);
    }

    /**
     * 넉백 속도와 지속시간을 설정한다. 양수면 오른쪽, 음수면 왼쪽.
     * 지속 중에는 state 의 update 대신 넉백 이동이 우선한다.
     */
    public void applyKnockback(double vx, long durationMs) {
        this.knockbackVx = vx;
        this.knockbackUntilMs = System.currentTimeMillis() + Math.max(0, durationMs);
        this.vx = vx;
    }

    /** 넉백이 끝나는 순간 적용할 다음 상태를 지정한다. 보통 추격(ChaseState)을 꽂는다. */
    public void setPostKnockbackState(MonsterState next) {
        this.postKnockbackState = next;
    }

    public boolean isKnockedBack(long now) { return now < knockbackUntilMs; }

    public void update(long dtMs) {
        long now = System.currentTimeMillis();
        if (now < knockbackUntilMs) {
            // 넉백 구간: 배회 경계를 넘지 않도록 clamp 하고 이동 처리만 한다.
            double nextX = x + knockbackVx * (dtMs / 1000.0);
            nextX = Math.max(minX, Math.min(maxX, nextX));
            this.x = nextX;
            this.vx = knockbackVx;
            return;
        }
        // 넉백이 방금 끝났으면 state 가 기대하는 속도로 복귀시킨다.
        if (knockbackUntilMs != 0 && now >= knockbackUntilMs) {
            knockbackUntilMs = 0;
            knockbackVx = 0;
            MonsterState next = postKnockbackState;
            postKnockbackState = null;
            // 추격이 꽂혀 있으면 플레이어를 향해, 아니면 기본 Idle → Wander 사이클.
            transitionTo(next != null ? next : new mygame.game.ai.IdleState());
        }
        if (state != null) {
            state.update(this, dtMs);
        }
    }
}
