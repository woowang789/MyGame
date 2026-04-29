package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository.Entry;
import mygame.admin.view.Html;

/** GET /admin/audit — 최근 admin 행위 로그 50 건 표시. */
public final class AuditHandler implements HttpHandler {

    private static final int LIMIT = 50;

    private final AdminFacade facade;

    public AuditHandler(AdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        List<Entry> entries = facade.recentAudit(LIMIT);
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>감사 로그</h1>");
        if (entries.isEmpty()) {
            sb.append("<p class=\"empty\">기록 없음</p>");
        } else {
            sb.append("<table class=\"data-table\"><thead><tr>")
              .append("<th>시각</th><th>관리자</th><th>액션</th><th>payload</th>")
              .append("</tr></thead><tbody>");
            for (Entry e : entries) {
                sb.append("<tr>")
                  .append("<td>").append(e.createdAt() == null ? "-" : Html.esc(e.createdAt().toString())).append("</td>")
                  .append("<td>").append(Html.esc(e.adminUsername())).append("</td>")
                  .append("<td>").append(Html.esc(e.action())).append("</td>")
                  .append("<td><code>").append(Html.esc(e.payload())).append("</code></td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table>");
        }
        HttpUtils.sendHtml(ex, 200, Html.layout("Audit", sb.toString()));
    }
}
