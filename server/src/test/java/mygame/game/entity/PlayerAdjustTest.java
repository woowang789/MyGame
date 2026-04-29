package mygame.game.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Player.adjustMeso/adjustExp 의 클램프와 자동 레벨업 미발동 동작을 회귀 테스트로 고정.
 *
 * <p>핵심 행동:
 * <ul>
 *   <li>음수 페널티는 0 미만으로 떨어지지 않는다.
 *   <li>큰 양수 EXP 를 더해도 자동 레벨업 cascade 가 발생하지 않는다 — 레벨은 별개 명령.
 * </ul>
 */
class PlayerAdjustTest {

    @Test
    @DisplayName("adjustMeso: 양수 가산, 음수 차감, 0 미만은 0 으로 클램프")
    void adjust_meso_clamps() {
        Player p = newPlayer();
        // 초기 메소 0. 양수 가산.
        assertEquals(1000, p.adjustMeso(1000));
        // 음수 차감 — 정상 범위.
        assertEquals(700, p.adjustMeso(-300));
        // 가진 것보다 큰 차감 — 0 으로 클램프.
        assertEquals(0, p.adjustMeso(-99999));
        // 음수 누적 시도 — 여전히 0.
        assertEquals(0, p.adjustMeso(-1));
    }

    @Test
    @DisplayName("adjustExp: 양수 가산해도 레벨업 cascade 가 트리거되지 않는다")
    void adjust_exp_does_not_level_up() {
        Player p = newPlayer();
        int initialLevel = p.level();
        // 1레벨 expToNext 이상의 큰 값을 한 번에 더해도 레벨은 그대로여야 한다.
        p.adjustExp(1_000_000);
        assertEquals(1_000_000, p.exp());
        assertEquals(initialLevel, p.level(), "admin 보정은 레벨업 cascade 를 트리거하지 않아야 함");
    }

    @Test
    @DisplayName("adjustExp: 음수는 0 으로 클램프")
    void adjust_exp_clamps_negative() {
        Player p = newPlayer();
        p.adjustExp(500);
        assertEquals(0, p.adjustExp(-99999));
    }

    private static Player newPlayer() {
        // dbId 양수 invariant 만 충족되면 됨. 네트워크 connection 은 본 테스트에서 사용 안 함.
        return new Player(1, 1L, "tester", null, "henesys", 80, 100);
    }
}
