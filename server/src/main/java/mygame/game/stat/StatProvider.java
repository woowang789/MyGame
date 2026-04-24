package mygame.game.stat;

/**
 * 스탯 공급자(Decorator 패턴의 Component 역할).
 *
 * <p>구현체 분류:
 * <ul>
 *   <li>기반(Concrete Component): {@link BaseStats} — 레벨 기반 기본 스탯
 *   <li>장식(Decorator): {@link EquipmentStatDecorator} — 다른 StatProvider 를 감싸
 *       장비 보너스를 누적한다. 여러 장비가 있으면 데코레이터가 체인을 이룬다.
 * </ul>
 *
 * <p>왜 Decorator 인가: 장비 슬롯이 늘어나도 {@code stats()} 호출 지점은 변하지 않고,
 * 합산 규칙은 데코레이터 한 곳에만 존재한다. if-else 로 "헬멧 있으면 +X, 무기 있으면 +Y"
 * 를 늘어놓는 것보다 확장에 열려 있고 변경에 닫혀 있다.
 */
public interface StatProvider {

    Stats stats();
}
