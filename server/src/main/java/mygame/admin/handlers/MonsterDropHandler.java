package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.MonsterDropDeleteCommand;
import mygame.admin.command.MonsterDropUpsertCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import mygame.game.item.ItemRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /admin/actions/monster-drop-{upsert|delete} — drop table 한 라인 편집.
 *
 * <p>경로 prefix 로 분기 (StaticAssetHandler 와 같은 패턴).
 */
public final class MonsterDropHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(MonsterDropHandler.class);

    private final AdminFacade facade;
    private final AuditLogRepository audit;
    private final boolean isDelete;

    public MonsterDropHandler(AdminFacade facade, AuditLogRepository audit, boolean isDelete) {
        this.facade = facade;
        this.audit = audit;
        this.isDelete = isDelete;
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
            String itemId = form.getOrDefault("itemId", "").trim();
            if (monsterId.isEmpty() || itemId.isEmpty()) {
                HttpUtils.sendHtml(ex, 400, "<span class=\"error\">monsterId / itemId 가 비어있습니다.</span>");
                return;
            }
            String message;
            if (isDelete) {
                message = new MonsterDropDeleteCommand(facade, monsterId, itemId)
                        .execute(AuthFilter.sessionOf(ex), audit);
            } else {
                // upsert: ItemRegistry 등록 검증 + 0..1 범위 검증
                try {
                    ItemRegistry.get(itemId);
                } catch (IllegalArgumentException ie) {
                    HttpUtils.sendHtml(ex, 400, "<span class=\"error\">" + Html.esc("ItemRegistry 에 없는 itemId: " + itemId) + "</span>");
                    return;
                }
                double chance = Double.parseDouble(form.getOrDefault("chance", "0").trim());
                int sortOrder = Integer.parseInt(form.getOrDefault("sortOrder", "0").trim());
                if (chance < 0.0 || chance > 1.0) {
                    HttpUtils.sendHtml(ex, 400, "<span class=\"error\">chance 는 0..1 범위여야 합니다.</span>");
                    return;
                }
                message = new MonsterDropUpsertCommand(facade, monsterId, itemId, chance, sortOrder)
                        .execute(AuthFilter.sessionOf(ex), audit);
            }
            HttpUtils.sendHtml(ex, 200, "<span class=\"ok\">" + Html.esc(message) + "</span>");
        } catch (NumberFormatException nfe) {
            HttpUtils.sendHtml(ex, 400, "<span class=\"error\">숫자 형식 오류</span>");
        } catch (Exception e) {
            log.error("monster-drop 처리 실패", e);
            HttpUtils.sendHtml(ex, 500, "<span class=\"error\">내부 오류</span>");
        }
    }
}
