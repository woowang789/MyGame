package mygame.game.stat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Decorator 한 겹의 합산 동작과 체인 합성 검증.
 *
 * <p>학습 포인트: 데코레이터를 여러 겹 감싸도 결과가 단순히
 * "베이스 + 모든 보너스" 와 동일해야 한다는 결합법칙.
 */
class EquipmentStatDecoratorTest {

    @Test
    @DisplayName("한 겹: base + bonus 가 결과 stats 가 된다")
    void singleLayer_addsBonusToBase() {
        StatProvider base = () -> new Stats(50, 25, 20, 200);
        StatProvider weapon = new EquipmentStatDecorator(base, new Stats(0, 0, 10, 0));

        Stats s = weapon.stats();
        assertEquals(50, s.maxHp());
        assertEquals(25, s.maxMp());
        assertEquals(30, s.attack());
        assertEquals(200, s.speed());
    }

    @Test
    @DisplayName("여러 겹: 모든 보너스가 누적되며 감싸는 순서와 무관하다")
    void multipleLayers_accumulateAndAreOrderInsensitive() {
        StatProvider base = () -> new Stats(50, 25, 20, 200);
        Stats weaponBonus = new Stats(0, 0, 10, 0);
        Stats hatBonus = new Stats(15, 5, 0, 0);
        Stats armorBonus = new Stats(25, 0, 0, 0);

        StatProvider chainA = new EquipmentStatDecorator(
                new EquipmentStatDecorator(
                        new EquipmentStatDecorator(base, weaponBonus),
                        hatBonus),
                armorBonus);

        StatProvider chainB = new EquipmentStatDecorator(
                new EquipmentStatDecorator(
                        new EquipmentStatDecorator(base, armorBonus),
                        weaponBonus),
                hatBonus);

        assertEquals(chainA.stats(), chainB.stats());
        // 합계 검증: 50+15+25=90, 25+5=30, 20+10=30, 200
        Stats expected = new Stats(90, 30, 30, 200);
        assertEquals(expected, chainA.stats());
    }

    @Test
    @DisplayName("null bonus: ZERO 로 취급되어 base 와 동일한 stats 를 반환")
    void nullBonus_treatedAsZero() {
        StatProvider base = () -> new Stats(50, 25, 20, 200);
        StatProvider chain = new EquipmentStatDecorator(base, null);
        assertEquals(base.stats(), chain.stats());
    }
}
