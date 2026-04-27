package mygame.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link MovementValidator} 의 좌표 검증 로직 단위 테스트.
 *
 * <p>학습 포인트: 서버 권위 모델에서 "정상 클라는 통과시키고, 명백한 부정만 차단"
 * 하는 임계치 설계 — false positive(정상 차단)와 false negative(부정 통과) 사이의
 * 트레이드오프가 테스트 케이스에 그대로 드러난다.
 */
class MovementValidatorTest {

    @Nested
    @DisplayName("정상 입력은 통과")
    class HappyPath {

        @Test
        @DisplayName("첫 이동(lastMoveAt=0)은 비교 대상이 없어 무조건 통과")
        void firstMove_alwaysAccepted() {
            MovementValidator.Result r = MovementValidator.validate(
                    100, 200, 9999, -9999, 0L, 1_000_000L);
            assertTrue(r.accepted());
        }

        @Test
        @DisplayName("정상 수평 이동(<= MAX_SPEED * dt)은 통과")
        void normalHorizontalMove_accepted() {
            // 100ms 동안 20px 이동 — MAX_HORIZONTAL_SPEED(200)*0.1*tolerance(1.5)=30 안쪽
            MovementValidator.Result r = MovementValidator.validate(
                    100, 200, 120, 200, 1_000L, 1_100L);
            assertTrue(r.accepted());
        }

        @Test
        @DisplayName("점프 직후 큰 수직 이동도 MAX_VERTICAL_SPEED 안이면 통과")
        void jumpDescent_accepted() {
            // 100ms 동안 100px 낙하 — MAX_VERTICAL_SPEED(1200)*0.1*1.5=180 안쪽
            MovementValidator.Result r = MovementValidator.validate(
                    100, 200, 100, 300, 1_000L, 1_100L);
            assertTrue(r.accepted());
        }
    }

    @Nested
    @DisplayName("비정상 입력은 거부")
    class Rejected {

        @Test
        @DisplayName("순간이동(매우 짧은 dt 에 큰 이동량)은 거부")
        void teleport_rejected() {
            // 100ms 동안 500px 수평 이동 — MAX_HORIZONTAL 임계 30 의 16배
            MovementValidator.Result r = MovementValidator.validate(
                    100, 200, 600, 200, 1_000L, 1_100L);
            assertFalse(r.accepted());
        }

        @Test
        @DisplayName("NaN 좌표는 즉시 거부")
        void nanCoord_rejected() {
            MovementValidator.Result r = MovementValidator.validate(
                    100, 200, Double.NaN, 200, 1_000L, 1_100L);
            assertFalse(r.accepted());
        }

        @Test
        @DisplayName("Infinity 좌표는 즉시 거부")
        void infiniteCoord_rejected() {
            MovementValidator.Result r = MovementValidator.validate(
                    100, 200, Double.POSITIVE_INFINITY, 200, 1_000L, 1_100L);
            assertFalse(r.accepted());
        }

        @Test
        @DisplayName("dt 가 매우 길어도 MAX_DT_MS 클램프로 무한 점프는 차단")
        void hugeIdle_thenJump_rejected() {
            // 10초 idle 후 5000px 점프 — MAX_DT_MS(500ms) 클램프로 임계는 200*1.5*0.5=150
            MovementValidator.Result r = MovementValidator.validate(
                    100, 200, 5100, 200, 1_000L, 11_000L);
            assertFalse(r.accepted());
        }
    }
}
