package mygame.game.stat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Stats} 의 불변성과 합산 동작 검증.
 * Decorator 체인이 신뢰할 수 있는 기반인지 확인하는 가장 기초적인 가드.
 */
class StatsTest {

    @Test
    @DisplayName("plus: 두 스탯의 각 필드가 합산되어 새 인스턴스로 반환된다")
    void plus_returnsNewSummedInstance() {
        Stats a = new Stats(100, 50, 20, 200);
        Stats b = new Stats(15, 5, 10, 0);

        Stats sum = a.plus(b);

        assertEquals(115, sum.maxHp());
        assertEquals(55, sum.maxMp());
        assertEquals(30, sum.attack());
        assertEquals(200, sum.speed());
        // 원본은 변하지 않음(불변성).
        assertEquals(100, a.maxHp());
        assertNotSame(a, sum);
    }

    @Test
    @DisplayName("plus(null): 자기 자신을 그대로 반환한다(데코레이터가 null bonus 를 안전히 다루기 위함)")
    void plus_null_returnsSelf() {
        Stats a = new Stats(10, 5, 3, 100);
        assertSame(a, a.plus(null));
    }

    @Test
    @DisplayName("ZERO + x = x: 항등원으로 동작한다")
    void zero_isAdditiveIdentity() {
        Stats x = new Stats(7, 3, 2, 50);
        assertEquals(x, Stats.ZERO.plus(x));
        assertEquals(x, x.plus(Stats.ZERO));
    }
}
