package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import mygame.admin.AdminFacade;
import mygame.admin.AdminFacade.OnlinePlayerView;
import mygame.admin.HttpUtils;
import mygame.admin.view.Html;

/**
 * htmx 폴링이 호출하는 부분 HTML 위젯들.
 *
 * <p>완전한 페이지를 만들지 않고 <em>스니펫</em> 만 반환 — htmx 가 outerHTML/innerHTML
 * 로 swap 한다. 라우팅은 path 기반:
 * <ul>
 *   <li>{@code /admin/widgets/online-count}</li>
 *   <li>{@code /admin/widgets/online-list}</li>
 * </ul>
 */
public final class WidgetsHandler implements HttpHandler {

    private static final int ONLINE_LIST_LIMIT = 50;

    private final AdminFacade facade;

    public WidgetsHandler(AdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        switch (path) {
            case "/admin/widgets/online-count" -> {
                HttpUtils.sendHtml(ex, 200, Integer.toString(facade.onlineCount()));
            }
            case "/admin/widgets/online-list" -> {
                List<OnlinePlayerView> views = facade.onlineSnapshot(ONLINE_LIST_LIMIT);
                HttpUtils.sendHtml(ex, 200, renderOnlineTable(views));
            }
            default -> HttpUtils.sendText(ex, 404, "Not Found: " + path);
        }
    }

    private static String renderOnlineTable(List<OnlinePlayerView> views) {
        if (views.isEmpty()) return "<p class=\"empty\">접속 중인 플레이어 없음</p>";
        StringBuilder sb = new StringBuilder();
        sb.append("<table class=\"data-table\"><thead><tr>")
          .append("<th>SID</th><th>이름</th><th>맵</th><th>Lv</th><th>HP</th><th>MP</th>")
          .append("</tr></thead><tbody>");
        for (OnlinePlayerView v : views) {
            sb.append("<tr>")
              .append("<td>").append(v.sessionId()).append("</td>")
              .append("<td>").append(Html.esc(v.name())).append("</td>")
              .append("<td>").append(Html.esc(v.mapId())).append("</td>")
              .append("<td>").append(v.level()).append("</td>")
              .append("<td>").append(v.hp()).append("</td>")
              .append("<td>").append(v.mp()).append("</td>")
              .append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }
}
