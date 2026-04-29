package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.view.Html;
import mygame.db.AccountRepository.AccountSummary;

/** GET /admin/accounts?page=N — 페이지네이션된 게임 계정 목록(읽기 전용). */
public final class AccountsHandler implements HttpHandler {

    private static final int PAGE_SIZE = 20;

    private final AdminFacade facade;

    public AccountsHandler(AdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        Map<String, String> q = HttpUtils.queryParams(ex);
        int page = Math.max(0, HttpUtils.intParam(q, "page", 0));
        int offset = page * PAGE_SIZE;
        long total = facade.accountCount();
        List<AccountSummary> list = facade.accountsPage(offset, PAGE_SIZE);

        StringBuilder sb = new StringBuilder();
        sb.append("<h1>계정 목록</h1>")
          .append("<p>총 ").append(total).append(" 개</p>");
        if (list.isEmpty()) {
            sb.append("<p class=\"empty\">표시할 계정이 없습니다.</p>");
        } else {
            sb.append("<table class=\"data-table\"><thead><tr>")
              .append("<th>ID</th><th>아이디</th><th>가입일</th>")
              .append("</tr></thead><tbody>");
            for (AccountSummary a : list) {
                sb.append("<tr>")
                  .append("<td>").append(a.id()).append("</td>")
                  .append("<td>").append(Html.esc(a.username())).append("</td>")
                  .append("<td>").append(a.createdAt() == null ? "-" : Html.esc(a.createdAt().toString())).append("</td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table>");
        }

        // 페이지네이션. 마지막 페이지 판정은 size < PAGE_SIZE 기준 — count 와 정확히
        // 맞추는 건 학습 단계에서는 과한 정밀도. 단순 prev/next 만 노출.
        sb.append("<nav class=\"pager\">");
        if (page > 0) sb.append("<a href=\"/admin/accounts?page=").append(page - 1).append("\">← 이전</a>");
        if (list.size() == PAGE_SIZE && (offset + PAGE_SIZE) < total) {
            sb.append("<a href=\"/admin/accounts?page=").append(page + 1).append("\">다음 →</a>");
        }
        sb.append("</nav>");

        HttpUtils.sendHtml(ex, 200, Html.layout("Accounts", sb.toString()));
    }
}
