package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.ItemDeleteCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** POST /admin/actions/item-delete — 참조 검사 후 삭제 또는 차단. */
public final class ItemDeleteHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ItemDeleteHandler.class);

    private final AdminFacade facade;
    private final AuditLogRepository audit;

    public ItemDeleteHandler(AdminFacade facade, AuditLogRepository audit) {
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
            String itemId = form.getOrDefault("itemId", "").trim();
            if (itemId.isEmpty()) {
                HttpUtils.sendHtml(ex, 400, "<span class=\"error\">itemId 가 비어있습니다.</span>");
                return;
            }
            String message = new ItemDeleteCommand(facade, itemId)
                    .execute(AuthFilter.sessionOf(ex), audit);
            HttpUtils.sendHtml(ex, 200, "<span class=\"ok\">" + Html.esc(message) + "</span>");
        } catch (Exception e) {
            log.error("아이템 delete 실패", e);
            HttpUtils.sendHtml(ex, 500, "<span class=\"error\">내부 오류</span>");
        }
    }
}
