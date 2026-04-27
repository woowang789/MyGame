package mygame;

import mygame.network.GameServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MyGame 서버 진입점.
 *
 * Phase A: 단순 에코 WebSocket 서버로 시작.
 * 이후 Phase에서 게임 루프, 맵, 채널 등이 추가된다.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final int DEFAULT_PORT = 9999;

    private Main() {
        // 인스턴스화 방지
    }

    public static void main(String[] args) throws InterruptedException {
        int port = resolvePort(args);

        GameServer server = new GameServer(port);
        server.start();
        log.info("MyGame 서버가 포트 {}에서 시작되었습니다.", port);

        // Graceful shutdown: Ctrl+C 시 안전하게 종료. 마지막 자동 저장도 강제 실행해
        // 종료 직전 진행도(주로 인벤토리·HP/MP) 분실을 줄인다.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("서버 종료 신호 수신. 정리 중...");
            try {
                server.shutdownPersistence();
                server.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown-hook"));

        // 메인 스레드는 대기 (서버는 별도 스레드에서 동작)
        Thread.currentThread().join();
    }

    private static int resolvePort(String[] args) {
        if (args.length == 0) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            log.warn("포트 인자가 숫자가 아닙니다: {}. 기본 포트 {} 사용.", args[0], DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }
}
