package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import mygame.admin.AdminFacade;
import mygame.admin.AdminFacade.ServerStats;
import mygame.admin.HttpUtils;
import mygame.admin.view.Html;

/**
 * GET /admin — 대시보드 메인. htmx 가 5 초 폴링으로 위젯을 부분 갱신한다.
 *
 * <p>설계 메모: SSE 도입은 라이브 로그가 추가되는 Phase 2 에서 — 현재는
 * 폴링만으로 충분하고 구현·디버깅 비용이 낮다.
 */
public final class DashboardHandler implements HttpHandler {

    private final AdminFacade facade;

    public DashboardHandler(AdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        ServerStats s = facade.stats();
        long uptimeMin = s.uptimeSeconds() / 60;
        String body = """
                <h1>대시보드</h1>
                <section class="cards">
                  <div class="card">
                    <h3>접속자</h3>
                    <div id="online-count" hx-get="/admin/widgets/online-count" hx-trigger="every 5s" hx-swap="innerHTML">%d</div>
                  </div>
                  <div class="card">
                    <h3>JVM Heap</h3>
                    <div>%d / %d MB</div>
                  </div>
                  <div class="card">
                    <h3>Uptime</h3>
                    <div>%d 분</div>
                  </div>
                </section>

                <section>
                  <h2>접속자 목록</h2>
                  <div id="online-list" hx-get="/admin/widgets/online-list" hx-trigger="load, every 5s" hx-swap="innerHTML">
                    로딩 중...
                  </div>
                </section>

                <section>
                  <h2>운영 명령</h2>
                  <form hx-post="/admin/actions/force-save" hx-target="#force-save-result" hx-swap="innerHTML"
                        hx-confirm="모든 접속자 상태를 즉시 DB 에 저장합니다. 진행할까요?">
                    <button type="submit">강제 저장</button>
                  </form>
                  <p id="force-save-result" class="action-result"></p>
                </section>
                """.formatted(s.onlineCount(), s.heapUsedMb(), s.heapMaxMb(), uptimeMin);
        HttpUtils.sendHtml(ex, 200, Html.layout("Admin Dashboard", body));
    }
}
