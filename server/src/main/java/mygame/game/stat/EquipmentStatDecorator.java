package mygame.game.stat;

/**
 * 장비 한 점을 한 겹의 데코레이터로 표현.
 *
 * <p>구조: {@code new EquipmentStatDecorator(prev, bonus)} 로 감싸면
 * {@code prev.stats().plus(bonus)} 가 된다. 장비가 여러 개면
 * 체인: {@code base -> weapon -> hat -> armor ...} 로 계속 감싼다.
 *
 * <p>이 방식의 장점:
 * <ul>
 *   <li>호출자는 항상 {@link StatProvider#stats()} 한 줄만 쓰면 된다.
 *   <li>장비 슬롯 추가가 {@link Stats} 나 Player 코드를 변경하지 않는다.
 *   <li>버프/디버프도 같은 데코레이터로 확장 가능.
 * </ul>
 */
public final class EquipmentStatDecorator implements StatProvider {

    private final StatProvider inner;
    private final Stats bonus;

    public EquipmentStatDecorator(StatProvider inner, Stats bonus) {
        this.inner = inner;
        this.bonus = bonus == null ? Stats.ZERO : bonus;
    }

    @Override
    public Stats stats() {
        return inner.stats().plus(bonus);
    }
}
