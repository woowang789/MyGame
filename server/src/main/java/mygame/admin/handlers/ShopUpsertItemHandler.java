package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.ShopUpsertItemCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import mygame.game.item.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** POST /admin/actions/shop-upsert-item — 가격/재고/순서 갱신 또는 새 라인 추가. */
public final class ShopUpsertItemHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ShopUpsertItemHandler.class);

    private final AdminFacade facade;
    private final AuditLogRepository audit;

    public ShopUpsertItemHandler(AdminFacade facade, AuditLogRepository audit) {
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
                HttpUtils.sendHtml(ex, 400, errorSpan("shopId / itemId 가 비어있습니다."));
                return;
            }
            // ItemRegistry 가 모르는 itemId 를 카탈로그에 넣지 못하게 막음 — ShopRegistry 가
            // 다음 reload 시점에 validateAll 로 한 번 더 잡지만, 입력 시점 거부가 친절.
            try {
                ItemRegistry.get(itemId);
            } catch (IllegalArgumentException ie) {
                HttpUtils.sendHtml(ex, 400, errorSpan("ItemRegistry 에 없는 itemId: " + itemId));
                return;
            }
            long price = Long.parseLong(form.getOrDefault("price", "0").trim());
            int stockPerTx = Integer.parseInt(form.getOrDefault("stockPerTx", "1").trim());
            int sortOrder = Integer.parseInt(form.getOrDefault("sortOrder", "0").trim());
            if (price <= 0) {
                HttpUtils.sendHtml(ex, 400, errorSpan("가격은 양수여야 합니다."));
                return;
            }
            if (stockPerTx <= 0) {
                HttpUtils.sendHtml(ex, 400, errorSpan("1회 최대 수량은 양수여야 합니다."));
                return;
            }

            var cmd = new ShopUpsertItemCommand(facade, shopId, itemId, price, stockPerTx, sortOrder);
            String message = cmd.execute(AuthFilter.sessionOf(ex), audit);
            HttpUtils.sendHtml(ex, 200, "<span class=\"ok\">" + Html.esc(message) + "</span>");
        } catch (NumberFormatException nfe) {
            HttpUtils.sendHtml(ex, 400, errorSpan("숫자 형식 오류"));
        } catch (Exception e) {
            log.error("상점 아이템 upsert 실패", e);
            HttpUtils.sendHtml(ex, 500, errorSpan("내부 오류"));
        }
    }

    private static String errorSpan(String text) {
        return "<span class=\"error\">" + Html.esc(text) + "</span>";
    }
}
