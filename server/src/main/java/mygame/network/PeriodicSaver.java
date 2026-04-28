package mygame.network;

import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import mygame.db.PlayerRepository;
import mygame.game.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 접속 중인 플레이어 상태를 주기적으로 DB 에 저장.
 *
 * <p>지금까지의 저장 정책은 onClose(연결 종료) 한 번이었다. 서버 크래시·전원 단절·
 * 강제 kill 시에는 마지막 세션 진척이 통째로 사라진다. 본 클래스는 일정 간격으로
 * 모든 접속 플레이어를 한 번씩 훑어 진행도/인벤토리/장비/HP·MP 를 저장한다.
 *
 * <p>설계 메모(학습):
 * <ul>
 *   <li>저장 작업은 단일 스레드 {@link ScheduledExecutorService} 에서 직렬 실행.
 *       동시에 같은 플레이어 두 번 저장되는 일을 구조적으로 차단한다.
 *   <li>플레이어 목록은 {@link Supplier} 로 주입받아 GameServer 의 sessionPlayers
 *       에 본 클래스가 직접 의존하지 않게 했다. 테스트에서 임의 Collection 주입 가능.
 *   <li>한 명의 저장 실패가 전체 주기를 망치지 않도록 try/catch 로 격리.
 * </ul>
 */
public final class PeriodicSaver {

    private static final Logger log = LoggerFactory.getLogger(PeriodicSaver.class);

    private final PlayerRepository repo;
    private final Supplier<Collection<Player>> playersSupplier;
    private final long intervalSeconds;
    private final ScheduledExecutorService exec;

    public PeriodicSaver(PlayerRepository repo,
                         Supplier<Collection<Player>> playersSupplier,
                         long intervalSeconds) {
        this.repo = repo;
        this.playersSupplier = playersSupplier;
        this.intervalSeconds = intervalSeconds;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "periodic-saver");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        exec.scheduleAtFixedRate(this::saveAll,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("주기 자동 저장 시작: {}초 간격", intervalSeconds);
    }

    /** 한 사이클. 외부에서 즉시 호출 가능(테스트용 + 셧다운 직전 강제 저장). */
    public void saveAll() {
        Collection<Player> snapshot = playersSupplier.get();
        if (snapshot.isEmpty()) return;
        int ok = 0, fail = 0;
        for (Player p : snapshot) {
            // Player 생성 invariant 로 dbId 는 항상 유효(양수). 별도 스킵 가드 불필요.
            try {
                repo.save(
                        p.dbId(),
                        p.level(),
                        p.exp(),
                        p.meso(),
                        p.hp(),
                        p.mp(),
                        p.inventory().snapshot(),
                        SessionNotifier.equipmentSnapshotAsStringMap(p));
                ok++;
            } catch (Exception e) {
                fail++;
                log.error("주기 저장 실패: name={}", p.name(), e);
            }
        }
        if (ok > 0 || fail > 0) {
            log.info("주기 자동 저장 완료: ok={}, fail={}", ok, fail);
        }
    }

    public void shutdown() {
        exec.shutdown();
        try {
            // 진행 중인 저장 사이클이 있으면 잠깐 대기
            if (!exec.awaitTermination(2, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
