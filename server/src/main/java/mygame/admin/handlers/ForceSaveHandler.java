package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.AdminCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /admin/actions/force-save — htmx 가 결과 메시지 부분 HTML 만 받아 swap.
 *
 * <p>실제 저장·audit 기록은 {@link AdminCommand} 구현체가 일관되게 처리.
 */
public final class ForceSaveHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ForceSaveHandler.class);

    private final AdminCommand command;
    private final AuditLogRepository audit;

    public ForceSaveHandler(AdminCommand command, AuditLogRepository audit) {
        this.command = command;
        this.audit = audit;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtils.sendText(ex, 405, "Method Not Allowed");
            return;
        }
        try {
            String message = command.execute(AuthFilter.sessionOf(ex), audit);
            HttpUtils.sendHtml(ex, 200, "<span class=\"ok\">" + Html.esc(message) + "</span>");
        } catch (Exception e) {
            log.error("강제 저장 명령 실패", e);
            HttpUtils.sendHtml(ex, 500, "<span class=\"error\">" + Html.esc("오류: " + e.getMessage()) + "</span>");
        }
    }
}
