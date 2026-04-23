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

    private double x;
    private double vx;
    private MonsterState state;

    public Monster(int id, String template,
                   double spawnX, double groundY,
                   double minX, double maxX, double speed) {
        this.id = id;
        this.template = template;
        this.x = spawnX;
        this.groundY = groundY;
        this.minX = minX;
        this.maxX = maxX;
        this.speed = speed;
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

    public void setX(double x) { this.x = x; }
    public void setVx(double vx) { this.vx = vx; }

    public void transitionTo(MonsterState next) {
        this.state = next;
        next.onEnter(this);
    }

    public void update(long dtMs) {
        if (state != null) {
            state.update(this, dtMs);
        }
    }
}
