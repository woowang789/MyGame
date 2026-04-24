package mygame.game.stat;

/**
 * 레벨 기반 기본 스탯. Decorator 체인의 Concrete Component.
 *
 * <p>공식은 단순 선형식. 이후 클래스/직업 도입 시 이 한 지점만 바꾸면 된다.
 */
public final class BaseStats implements StatProvider {

    private static final int BASE_MAX_HP = 50;
    private static final int HP_PER_LEVEL = 10;
    private static final int BASE_MAX_MP = 25;
    private static final int MP_PER_LEVEL = 3;
    private static final int BASE_ATTACK = 20;
    private static final int ATTACK_PER_LEVEL = 3;
    private static final int BASE_SPEED = 200;

    private final int level;

    public BaseStats(int level) {
        this.level = Math.max(1, level);
    }

    @Override
    public Stats stats() {
        return new Stats(
                BASE_MAX_HP + HP_PER_LEVEL * (level - 1),
                BASE_MAX_MP + MP_PER_LEVEL * (level - 1),
                BASE_ATTACK + ATTACK_PER_LEVEL * (level - 1),
                BASE_SPEED);
    }
}
