package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.KickPlayerCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /admin/actions/kick-player — form: accountId.
 * htmx 가 결과 메시지 부분 HTML 만 받아 swap 한다.
 */
public final class KickPlayerHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(KickPlayerHandler.class);

    private final AdminFacade facade;
    private final AuditLogRepository audit;

    public KickPlayerHandler(AdminFacade facade, AuditLogRepository audit) {
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
            var cmd = new KickPlayerCommand(facade, accountId);
            String message = cmd.execute(AuthFilter.sessionOf(ex), audit);
            HttpUtils.sendHtml(ex, 200, "<span class=\"ok\">" + Html.esc(message) + "</span>");
        } catch (NumberFormatException nfe) {
            HttpUtils.sendHtml(ex, 400,
                    "<span class=\"error\">" + Html.esc("accountId 형식 오류") + "</span>");
        } catch (Exception e) {
            log.error("플레이어 킥 실패", e);
            HttpUtils.sendHtml(ex, 500,
                    "<span class=\"error\">" + Html.esc("오류: " + e.getMessage()) + "</span>");
        }
    }
}
