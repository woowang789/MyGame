package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.ShopDeleteItemCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** POST /admin/actions/shop-delete-item — 카탈로그 한 라인 삭제. */
public final class ShopDeleteItemHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ShopDeleteItemHandler.class);

    private final AdminFacade facade;
    private final AuditLogRepository audit;

    public ShopDeleteItemHandler(AdminFacade facade, AuditLogRepository audit) {
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
            String shopId = form.getOrDefault("shopId", "").trim();
            String itemId = form.getOrDefault("itemId", "").trim();
            if (shopId.isEmpty() || itemId.isEmpty()) {
                HttpUtils.sendHtml(ex, 400, "<span class=\"error\">shopId / itemId 가 비어있습니다.</span>");
                return;
            }
            var cmd = new ShopDeleteItemCommand(facade, shopId, itemId);
            String message = cmd.execute(AuthFilter.sessionOf(ex), audit);
            HttpUtils.sendHtml(ex, 200, "<span class=\"ok\">" + Html.esc(message) + "</span>");
        } catch (Exception e) {
            log.error("상점 아이템 delete 실패", e);
            HttpUtils.sendHtml(ex, 500, "<span class=\"error\">내부 오류</span>");
        }
    }
}
