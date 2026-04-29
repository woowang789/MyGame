package mygame;

import java.io.IOException;
import mygame.admin.AdminFacade;
import mygame.admin.AdminServer;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.audit.JdbcAuditLogRepository;
import mygame.admin.auth.AdminAuth;
import mygame.admin.auth.JdbcAdminAccountRepository;
import mygame.network.GameServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MyGame 서버 진입점.
 *
 * <p>Phase A 의 에코 서버에서 출발해 현재는 게임 WebSocket 서버 + 백오피스 HTTP 서버
 * 를 같은 JVM 의 두 포트에서 띄운다. 백오피스는 도메인 게이트웨이({@link AdminFacade})
 * 를 통해서만 게임 상태에 접근한다.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final int DEFAULT_PORT = 9999;
    private static final int DEFAULT_ADMIN_PORT = 8088;

    private Main() {
        // 인스턴스화 방지
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        int port = resolvePort(args);
        int adminPort = resolveAdminPort();

        GameServer server = new GameServer(port);
        server.start();
        log.info("MyGame 서버가 포트 {}에서 시작되었습니다.", port);

        // 백오피스 wiring: 같은 Database 풀을 공유, World/Repo 는 GameServer 가 노출한 게터를 사용.
        var adminAccountRepo = new JdbcAdminAccountRepository(server.database());
        AuditLogRepository auditRepo = new JdbcAuditLogRepository(server.database());
        var adminAuth = new AdminAuth(adminAccountRepo);
        adminAuth.bootstrapIfEmpty(System.getenv("MYGAME_ADMIN_BOOTSTRAP"));

        var facade = new AdminFacade(
                server::onlinePlayersSnapshot,
                server.accountRepo(),
                server.playerRepo(),
                server.shopRepo(),
                auditRepo,
                server.periodicSaver()::saveAll,
                // 킥: 게임 서버 onClose 핸들러를 자연스럽게 트리거하도록 connection.close() 위임.
                p -> p.connection().close(),
                // 전체 공지: 직렬화·전 세션 송신 로직은 GameServer 가 캡슐화.
                server::broadcastSystemNotice);
        AdminServer adminServer = new AdminServer(adminPort, facade, adminAuth, auditRepo);
        adminServer.start();
        log.info("백오피스 HTTP 가 포트 {}에서 시작되었습니다.", adminPort);

        // Graceful shutdown: Ctrl+C 시 안전하게 종료. 마지막 자동 저장도 강제 실행해
        // 종료 직전 진행도(주로 인벤토리·HP/MP) 분실을 줄인다.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("서버 종료 신호 수신. 정리 중...");
            try {
                adminServer.stop();
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

    private static int resolveAdminPort() {
        String env = System.getenv("MYGAME_ADMIN_PORT");
        if (env == null || env.isBlank()) return DEFAULT_ADMIN_PORT;
        try {
            return Integer.parseInt(env.trim());
        } catch (NumberFormatException e) {
            log.warn("MYGAME_ADMIN_PORT 가 숫자가 아닙니다: {}. 기본 {} 사용.", env, DEFAULT_ADMIN_PORT);
            return DEFAULT_ADMIN_PORT;
        }
    }
}
