package mygame.game;

/**
 * 이동 패킷 sanity 검증기 (Phase: 클라이언트 이동의 서버 권위화 — 단계 B).
 *
 * <p>완전한 입력 권위(서버 물리)는 비용이 크므로, 일단은 "물리적으로 말이 안 되는"
 * 좌표 점프만 차단한다. 클라가 좌표를 보내고, 서버가 직전 좌표 + 경과 시간으로
 * 최대 이동 가능량을 계산해 그 안이면 통과시킨다.
 *
 * <p>임계치는 클라이언트의 {@code MOVE_SPEED}/{@code JUMP_VELOCITY} 와 일치시킨다.
 * 부동소수 오차·작은 가속(점프 직후 등)을 흡수하기 위해 {@link #TOLERANCE} 배수의
 * 여유를 둔다.
 *
 * <p><b>고의로 안 막는 것</b>: 정상 클라가 키 보내고 화면에서 움직이는 속도까지는
 * 통과해야 한다. 텔레포트 핵, 패킷 위조로 좌표 직접 조작 같은 명백한 부정만 잡는다.
 */
public final class MovementValidator {

    /** 클라 {@code GameScene.MOVE_SPEED} 와 동일. 변경 시 양쪽 함께 갱신. */
    public static final double MAX_HORIZONTAL_SPEED = 200.0;
    /**
     * 수직 최대 속도. 점프 초기 속도 |JUMP_VELOCITY|=450 + 중력 누적을 감안.
     * 너무 타이트하면 빠른 낙하 시 false-positive.
     */
    public static final double MAX_VERTICAL_SPEED = 1200.0;
    /** 부동소수 + 패킷 지터 흡수용 여유 배수. */
    public static final double TOLERANCE = 1.5;
    /** 첫 이동 또는 재진입 직후 검증 스킵용 sentinel(밀리초). */
    private static final long FIRST_MOVE = 0L;
    /** dt 가 너무 길면(연결 정체 등) 큰 점프가 합법화돼 검증 의미 상실. 상한 클램프. */
    private static final long MAX_DT_MS = 500;

    private MovementValidator() {}

    /** 검증 결과. {@code accepted} 가 false 면 호출자가 서버 좌표로 클라를 보정해야 한다. */
    public record Result(boolean accepted, String reason) {
        public static final Result OK = new Result(true, null);
        public static Result reject(String reason) {
            return new Result(false, reason);
        }
    }

    /**
     * 이동 패킷 좌표를 검증.
     *
     * @param prevX  서버 보유 직전 X
     * @param prevY  서버 보유 직전 Y
     * @param newX   요청된 X
     * @param newY   요청된 Y
     * @param lastMoveAt 직전 MOVE 처리 시각(ms). {@link #FIRST_MOVE} 면 검증 스킵
     * @param now    현재 시각(ms)
     */
    public static Result validate(double prevX, double prevY,
                                  double newX, double newY,
                                  long lastMoveAt, long now) {
        // 좌표 유한성. NaN/Infinity 는 즉시 거부.
        if (!Double.isFinite(newX) || !Double.isFinite(newY)) {
            return Result.reject("non-finite coordinate");
        }
        // 첫 패킷은 비교 대상이 없어 통과.
        if (lastMoveAt == FIRST_MOVE) {
            return Result.OK;
        }
        long dt = Math.min(MAX_DT_MS, Math.max(1, now - lastMoveAt));
        double maxDx = MAX_HORIZONTAL_SPEED * TOLERANCE * (dt / 1000.0);
        double maxDy = MAX_VERTICAL_SPEED * TOLERANCE * (dt / 1000.0);
        double dx = Math.abs(newX - prevX);
        double dy = Math.abs(newY - prevY);
        if (dx > maxDx) {
            return Result.reject("horizontal jump " + dx + "px > " + maxDx + "px in " + dt + "ms");
        }
        if (dy > maxDy) {
            return Result.reject("vertical jump " + dy + "px > " + maxDy + "px in " + dt + "ms");
        }
        return Result.OK;
    }
}
