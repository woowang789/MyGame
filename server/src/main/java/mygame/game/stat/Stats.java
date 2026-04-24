package mygame.game.stat;

/**
 * 플레이어 능력치(불변 값 객체).
 *
 * <p>레벨·장비·버프 등 여러 출처의 스탯을 합산할 때 매번 새 인스턴스를 만든다.
 * Decorator 패턴이 각 레이어에서 {@link #plus(Stats)} 로 누적 스탯을 반환할 수 있게
 * {@code record} 로 불변성을 강제한다.
 *
 * <p>Phase M(스킬): {@code maxMp} 추가. 스킬 비용 자원 관리 용도.
 */
public record Stats(int maxHp, int maxMp, int attack, int speed) {

    public static final Stats ZERO = new Stats(0, 0, 0, 0);

    public Stats plus(Stats other) {
        if (other == null) return this;
        return new Stats(
                this.maxHp + other.maxHp,
                this.maxMp + other.maxMp,
                this.attack + other.attack,
                this.speed + other.speed);
    }
}
