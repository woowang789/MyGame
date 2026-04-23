package mygame.game;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 고정 tick rate 로 모든 맵을 업데이트하는 서버 게임 루프.
 *
 * <p>30 tick/sec = 약 33ms 간격. {@link ScheduledExecutorService} 가 내부적으로
 * 단일 스레드에서 순차 실행하므로 맵 간 동시성 고려가 단순해진다.
 *
 * <p>tick 내부에서 예외가 발생해도 스케줄러가 이후 실행을 멈추지 않도록
 * try/catch 로 감싼다 ({@code scheduleAtFixedRate} 의 기본 동작은 예외 시 중단).
 */
public final class GameLoop {

    private static final Logger log = LoggerFactory.getLogger(GameLoop.class);
    private static final int TICK_RATE = 30;
    private static final long TICK_MS = 1000L / TICK_RATE;

    private final World world;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;
    private long lastTickMs = 0;

    public GameLoop(World world) {
        this.world = world;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "game-loop");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        lastTickMs = System.currentTimeMillis();
        task = scheduler.scheduleAtFixedRate(this::tickSafe, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        log.info("게임 루프 시작: {} tick/sec", TICK_RATE);
    }

    public void stop() {
        if (task != null) task.cancel(false);
        scheduler.shutdownNow();
    }

    private void tickSafe() {
        try {
            long now = System.currentTimeMillis();
            long dt = Math.max(1, now - lastTickMs);
            lastTickMs = now;
            for (GameMap map : world.maps()) {
                map.tick(dt);
            }
        } catch (Exception e) {
            log.error("게임 루프 tick 오류", e);
        }
    }
}
