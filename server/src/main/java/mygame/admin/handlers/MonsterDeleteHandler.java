package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.MonsterDeleteCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MonsterDeleteHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(MonsterDeleteHandler.class);

    private final AdminFacade facade;
    private final AuditLogRepository audit;

    public MonsterDeleteHandler(AdminFacade facade, AuditLogRepository audit) {
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
            String monsterId = form.getOrDefault("monsterId", "").trim();
            if (monsterId.isEmpty()) {
                HttpUtils.sendHtml(ex, 400, "<span class=\"error\">monsterId 가 비어있습니다.</span>");
                return;
            }
            String message = new MonsterDeleteCommand(facade, monsterId)
                    .execute(AuthFilter.sessionOf(ex), audit);
            HttpUtils.sendHtml(ex, 200, "<span class=\"ok\">" + Html.esc(message) + "</span>");
        } catch (Exception e) {
            log.error("몬스터 delete 실패", e);
            HttpUtils.sendHtml(ex, 500, "<span class=\"error\">내부 오류</span>");
        }
    }
}
