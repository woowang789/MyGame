package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.SetAccountDisabledCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import mygame.db.AccountRepository.AccountSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /admin/actions/account-disabled — 폼 데이터 (accountId, disabled) 로
 * {@link SetAccountDisabledCommand} 를 1회 실행. htmx 부분 응답으로 변경된 행 1줄을
 * 다시 그려 클라이언트가 outerHTML 로 swap 한다.
 */
public final class AccountDisabledHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountDisabledHandler.class);

    private final AdminFacade facade;
    private final AuditLogRepository audit;

    public AccountDisabledHandler(AdminFacade facade, AuditLogRepository audit) {
        this.facade = facade;
        this.audit = audit;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtils.sendText(ex, 405, "Method Not Allowed");
            return;
        }
        try {
            Map<String, String> form = HttpUtils.parseFormBody(ex);
            long accountId = Long.parseLong(form.getOrDefault("accountId", "0"));
            boolean disabled = "true".equals(form.get("disabled"));
            String username = form.getOrDefault("username", "");
            // 자기 자신 또는 잘못된 id 검증 — 본 단계에선 admin_accounts 와 분리돼 있어
            // self-ban 위험은 없지만, 잘못된 id 호출 시 명시적 메시지를 돌려준다.
            var cmd = new SetAccountDisabledCommand(facade, accountId, disabled);
            cmd.execute(AuthFilter.sessionOf(ex), audit);
            HttpUtils.sendHtml(ex, 200, AccountsHandler.renderAccountRow(
                    new AccountSummary(accountId, username, null, disabled)));
        } catch (NumberFormatException nfe) {
            HttpUtils.sendHtml(ex, 400,
                    "<tr><td colspan=\"4\" class=\"error\">" + Html.esc("잘못된 accountId") + "</td></tr>");
        } catch (Exception e) {
            log.error("계정 정지/해제 실패", e);
            HttpUtils.sendHtml(ex, 500,
                    "<tr><td colspan=\"4\" class=\"error\">" + Html.esc("오류: " + e.getMessage()) + "</td></tr>");
        }
    }
}
