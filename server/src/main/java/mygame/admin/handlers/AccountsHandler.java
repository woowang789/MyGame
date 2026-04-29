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

/** GET /admin/accounts?page=N — 페이지네이션된 게임 계정 목록 + 정지/해제 액션. */
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
              .append("<th>ID</th><th>아이디</th><th>가입일</th><th>상태</th>")
              .append("</tr></thead><tbody>");
            for (AccountSummary a : list) {
                sb.append(renderAccountRow(a));
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

    /**
     * 한 행을 그대로 렌더 — POST 응답에서도 동일 스니펫을 outerHTML 로 swap.
     * disabled 상태에 따라 ban/unban 토글 버튼을 다르게 표시.
     */
    public static String renderAccountRow(AccountSummary a) {
        String createdAt = a.createdAt() == null ? "-" : Html.esc(a.createdAt().toString());
        String stateBadge = a.disabled()
                ? "<span class=\"badge banned\">정지</span>"
                : "<span class=\"badge active\">활성</span>";
        boolean nextDisabled = !a.disabled();
        String btnLabel = nextDisabled ? "정지" : "해제";
        String confirmMsg = nextDisabled
                ? "계정 '" + a.username() + "'를 정지합니다. 진행할까요?"
                : "계정 '" + a.username() + "'의 정지를 해제합니다. 진행할까요?";
        StringBuilder sb = new StringBuilder();
        sb.append("<tr id=\"acc-row-").append(a.id()).append("\">")
          .append("<td>").append(a.id()).append("</td>")
          .append("<td><a href=\"/admin/accounts/").append(a.id()).append("\">")
          .append(Html.esc(a.username())).append("</a></td>")
          .append("<td>").append(createdAt).append("</td>")
          .append("<td>").append(stateBadge).append(" ")
          .append("<form hx-post=\"/admin/actions/account-disabled\" ")
          .append("hx-target=\"#acc-row-").append(a.id()).append("\" hx-swap=\"outerHTML\" ")
          .append("hx-confirm=\"").append(Html.esc(confirmMsg)).append("\" class=\"row-action\">")
          .append("<input type=\"hidden\" name=\"accountId\" value=\"").append(a.id()).append("\">")
          .append("<input type=\"hidden\" name=\"username\" value=\"").append(Html.esc(a.username())).append("\">")
          .append("<input type=\"hidden\" name=\"disabled\" value=\"").append(nextDisabled).append("\">")
          .append("<button type=\"submit\">").append(btnLabel).append("</button>")
          .append("</form>")
          .append("</td>")
          .append("</tr>");
        return sb.toString();
    }
}
