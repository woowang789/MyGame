package mygame.game.stat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BaseStats} 의 레벨별 계산식 검증.
 *
 * <p>공식이 바뀌면 본 테스트가 깨지면서 의도치 않은 변경을 감지한다(밸런스 변경 시
 * 테스트도 함께 수정해야 한다는 신호로 작동).
 */
class BaseStatsTest {

    @Test
    @DisplayName("레벨 1: 베이스 상수가 그대로 적용된다")
    void level1_returnsBaseConstants() {
        Stats s = new BaseStats(1).stats();

        assertEquals(50, s.maxHp());   // BASE_MAX_HP
        assertEquals(25, s.maxMp());   // BASE_MAX_MP
        assertEquals(20, s.attack());  // BASE_ATTACK
        assertEquals(200, s.speed());  // BASE_SPEED
    }

    @Test
    @DisplayName("레벨 5: 레벨당 증가량이 누적되어 적용된다")
    void level5_accumulatesPerLevelGains() {
        Stats s = new BaseStats(5).stats();

        // 50 + 10 * 4 = 90
        assertEquals(90, s.maxHp());
        // 25 + 3 * 4 = 37
        assertEquals(37, s.maxMp());
        // 20 + 3 * 4 = 32
        assertEquals(32, s.attack());
        // SPEED 는 레벨 영향 없음
        assertEquals(200, s.speed());
    }

    @Test
    @DisplayName("레벨 0/음수: 1 로 보정된다(부정 입력 방어)")
    void nonPositiveLevel_isClampedToOne() {
        Stats s0 = new BaseStats(0).stats();
        Stats sNeg = new BaseStats(-3).stats();
        Stats s1 = new BaseStats(1).stats();
        assertEquals(s1, s0);
        assertEquals(s1, sNeg);
    }
}
