package mygame.admin;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth;
import mygame.admin.command.AdminCommand;
import mygame.admin.command.ForceSaveCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.handlers.AccountDetailHandler;
import mygame.admin.handlers.AccountDisabledHandler;
import mygame.admin.handlers.AccountsHandler;
import mygame.admin.handlers.AdjustPlayerHandler;
import mygame.admin.handlers.AuditHandler;
import mygame.admin.handlers.BroadcastNoticeHandler;
import mygame.admin.handlers.DashboardHandler;
import mygame.admin.handlers.ForceSaveHandler;
import mygame.admin.handlers.ItemDeleteHandler;
import mygame.admin.handlers.ItemDetailHandler;
import mygame.admin.handlers.ItemUpsertHandler;
import mygame.admin.handlers.ItemsHandler;
import mygame.admin.handlers.KickPlayerHandler;
import mygame.admin.handlers.LoginHandler;
import mygame.admin.handlers.LogoutHandler;
import mygame.admin.handlers.ResetPasswordHandler;
import mygame.admin.handlers.ShopDeleteItemHandler;
import mygame.admin.handlers.ShopDetailHandler;
import mygame.admin.handlers.ShopUpsertItemHandler;
import mygame.admin.handlers.ShopsHandler;
import mygame.admin.handlers.StaticAssetHandler;
import mygame.admin.handlers.WidgetsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 백오피스 HTTP 서버 — 게임 WebSocket 과 별도 포트에서 listen.
 *
 * <p>JDK 내장 {@link HttpServer} 위에 라우팅 테이블·인증 필터·정적 자산 서빙을 직접
 * 짜본다. Spring 의 {@code DispatcherServlet}/필터 체인이 무엇을 추상화하는지
 * 체감하는 것이 학습 의도. 의존성 0 추가.
 */
public final class AdminServer {

    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);

    private final HttpServer http;
    private final ThreadPoolExecutor exec;

    public AdminServer(int port,
                       AdminFacade facade,
                       AdminAuth auth,
                       AuditLogRepository auditRepo) throws IOException {
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        // 정적 자산 + 인증 페이지는 가벼우므로 코어 4개 정도면 충분.
        this.exec = (ThreadPoolExecutor) Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "admin-http");
            t.setDaemon(true);
            return t;
        });
        this.http.setExecutor(exec);

        AdminCommand forceSave = new ForceSaveCommand(facade);

        // 공개 경로 — 인증 필터 미적용
        http.createContext("/admin/login", new LoginHandler(auth));
        http.createContext("/admin/logout", new LogoutHandler(auth));
        http.createContext("/admin/assets/", new StaticAssetHandler());
        // 루트 헬스체크 — 운영 모니터링이 ping 할 수 있는 단순 엔드포인트.
        http.createContext("/", (HttpHandler) ex -> {
            if ("/".equals(ex.getRequestURI().getPath())) {
                HttpUtils.redirect(ex, "/admin");
            } else {
                HttpUtils.sendText(ex, 404, "Not Found");
            }
        });

        AuthFilter authFilter = new AuthFilter(auth);
        protect(http.createContext("/admin", new DashboardHandler(facade)), authFilter);
        protect(http.createContext("/admin/widgets/", new WidgetsHandler(facade)), authFilter);
        // /admin/accounts/{id} 와 /admin/accounts 충돌 회피: HttpContext 는 prefix 매치라
        // 더 긴 경로(/admin/accounts/) 를 먼저 등록해야 detail 핸들러가 우선 매치된다.
        protect(http.createContext("/admin/accounts/", new AccountDetailHandler(facade)), authFilter);
        protect(http.createContext("/admin/accounts", new AccountsHandler(facade)), authFilter);
        protect(http.createContext("/admin/audit", new AuditHandler(facade)), authFilter);
        protect(http.createContext("/admin/actions/force-save",
                new ForceSaveHandler(forceSave, auditRepo)), authFilter);
        protect(http.createContext("/admin/actions/account-disabled",
                new AccountDisabledHandler(facade, auditRepo)), authFilter);
        protect(http.createContext("/admin/actions/adjust-player",
                new AdjustPlayerHandler(facade, auditRepo)), authFilter);
        protect(http.createContext("/admin/actions/reset-password",
                new ResetPasswordHandler(facade, auditRepo)), authFilter);
        protect(http.createContext("/admin/actions/kick-player",
                new KickPlayerHandler(facade, auditRepo)), authFilter);
        protect(http.createContext("/admin/actions/broadcast",
                new BroadcastNoticeHandler(facade, auditRepo)), authFilter);
        // /admin/shops/{id} (detail) 와 /admin/shops (list) — 더 긴 prefix 먼저 등록.
        protect(http.createContext("/admin/shops/", new ShopDetailHandler(facade)), authFilter);
        protect(http.createContext("/admin/shops", new ShopsHandler(facade)), authFilter);
        protect(http.createContext("/admin/actions/shop-upsert-item",
                new ShopUpsertItemHandler(facade, auditRepo)), authFilter);
        protect(http.createContext("/admin/actions/shop-delete-item",
                new ShopDeleteItemHandler(facade, auditRepo)), authFilter);
        // /admin/items/{id} 와 /admin/items — 더 긴 prefix 먼저.
        protect(http.createContext("/admin/items/", new ItemDetailHandler(facade)), authFilter);
        protect(http.createContext("/admin/items", new ItemsHandler(facade)), authFilter);
        protect(http.createContext("/admin/actions/item-upsert",
                new ItemUpsertHandler(facade, auditRepo)), authFilter);
        protect(http.createContext("/admin/actions/item-delete",
                new ItemDeleteHandler(facade, auditRepo)), authFilter);
    }

    private static void protect(HttpContext ctx, AuthFilter filter) {
        ctx.getFilters().add(filter);
    }

    public void start() {
        http.start();
        log.info("백오피스 HTTP 서버 listen: {}", http.getAddress());
    }

    public void stop() {
        // 진행 중 응답에 1 초 grace.
        http.stop(1);
        exec.shutdown();
        try {
            if (!exec.awaitTermination(2, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("백오피스 HTTP 서버 종료");
    }

    /** 외부 테스트에서 라우트 가시성 확인용. */
    public InetSocketAddress address() {
        return http.getAddress();
    }

    // 내부 핸들러에서 사용하기 위한 유틸 (HttpExchange → exception 안전 핸들 wrapping 등을
    // 추가할 자리. 현재는 별도 필요 없음).
    @SuppressWarnings("unused")
    private static void safeHandle(HttpExchange ex, HttpHandler h) {
        try {
            h.handle(ex);
        } catch (IOException e) {
            log.error("admin handler IO 오류", e);
        }
    }
}
