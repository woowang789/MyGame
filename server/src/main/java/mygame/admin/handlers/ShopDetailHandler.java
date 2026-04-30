package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Optional;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.view.FormRenderer;
import mygame.admin.view.Html;
import mygame.db.ShopRepository.ShopSummary;
import mygame.game.shop.ShopCatalog;

/**
 * GET /admin/shops/{id} — 단일 상점의 카탈로그 편집 페이지.
 *
 * <p>각 아이템 행은 자체 폼 — 가격/재고/순서 수정은 인라인 form, 삭제는 별도 버튼.
 * 행 단위 swap 으로 페이지 새로고침 없이 즉시 피드백.
 */
public final class ShopDetailHandler implements HttpHandler {

    private static final String PREFIX = "/admin/shops/";

    private final AdminFacade facade;

    public ShopDetailHandler(AdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith(PREFIX)) {
            HttpUtils.sendText(ex, 404, "Not Found");
            return;
        }
        String shopId = path.substring(PREFIX.length());
        if (shopId.isEmpty() || shopId.contains("/")) {
            HttpUtils.sendText(ex, 400, "잘못된 shopId");
            return;
        }
        Optional<ShopSummary> meta = facade.shopSummary(shopId);
        if (meta.isEmpty()) {
            HttpUtils.sendHtml(ex, 404, Html.layout("Shop not found",
                    "<h1>상점 없음</h1><p>id=" + Html.esc(shopId) + " 인 상점을 찾지 못했습니다.</p>"));
            return;
        }
        Optional<ShopCatalog> catalog = facade.shopCatalog(shopId);

        StringBuilder sb = new StringBuilder();
        sb.append("<p><a href=\"/admin/shops\">← 상점 목록</a></p>");
        sb.append("<h1>").append(Html.esc(meta.get().name()))
          .append(" <span class=\"muted\">").append(Html.esc(shopId)).append("</span></h1>");

        sb.append("<section><h2>판매 아이템</h2>");
        sb.append("<table class=\"data-table\" id=\"shop-items\"><thead><tr>")
          .append("<th>itemId</th><th>가격</th><th>1회 최대</th><th>순서</th><th>액션</th>")
          .append("</tr></thead><tbody>");
        if (catalog.isPresent()) {
            int order = 0;
            for (var entry : catalog.get().items()) {
                sb.append(renderItemRow(shopId, entry.itemId(),
                        entry.price(), entry.stockPerTransaction(), order));
                order++;
            }
        }
        sb.append(renderNewItemRow(shopId));
        sb.append("</tbody></table>");
        sb.append("<p id=\"shop-result\" class=\"action-result\"></p>");
        sb.append("</section>");

        HttpUtils.sendHtml(ex, 200, Html.layout("Shop " + shopId, sb.toString()));
    }

    /**
     * 한 아이템 행: 인라인 수정 form + 삭제 form. id 가 행마다 고유해야 swap 충돌이 없다.
     * 폼 이름 충돌 방지를 위해 input name 은 폼 안에서만 고유하면 충분.
     */
    private static String renderItemRow(String shopId, String itemId,
                                        long price, int stockPerTx, int sortOrder) {
        String rowId = "shop-row-" + FormRenderer.safeDomId(itemId);
        StringBuilder sb = new StringBuilder();
        sb.append("<tr id=\"").append(rowId).append("\">")
          .append("<td><code>").append(Html.esc(itemId)).append("</code></td>");
        // 가격/재고/순서 — inline 수정 form (단일)
        sb.append("<td colspan=\"3\">")
          .append("<form hx-post=\"/admin/actions/shop-upsert-item\" ")
          .append("hx-target=\"#shop-result\" hx-swap=\"innerHTML\" class=\"shop-row-form\">")
          .append("<input type=\"hidden\" name=\"shopId\" value=\"").append(Html.esc(shopId)).append("\">")
          .append("<input type=\"hidden\" name=\"itemId\" value=\"").append(Html.esc(itemId)).append("\">")
          .append("<input type=\"number\" name=\"price\" value=\"").append(price).append("\" required min=\"1\" step=\"1\">")
          .append("<input type=\"number\" name=\"stockPerTx\" value=\"").append(stockPerTx).append("\" required min=\"1\" step=\"1\">")
          .append("<input type=\"number\" name=\"sortOrder\" value=\"").append(sortOrder).append("\" required step=\"1\">")
          .append("<button type=\"submit\">저장</button>")
          .append("</form>")
          .append("</td>");
        sb.append("<td>")
          .append("<form hx-post=\"/admin/actions/shop-delete-item\" hx-target=\"#shop-result\" hx-swap=\"innerHTML\" ")
          .append("hx-confirm=\"" + Html.esc(itemId) + " 라인을 삭제합니다.\" class=\"row-action\">")
          .append("<input type=\"hidden\" name=\"shopId\" value=\"").append(Html.esc(shopId)).append("\">")
          .append("<input type=\"hidden\" name=\"itemId\" value=\"").append(Html.esc(itemId)).append("\">")
          .append("<button type=\"submit\">삭제</button>")
          .append("</form>")
          .append("</td>")
          .append("</tr>");
        return sb.toString();
    }

    /** 새 아이템 추가 — 마지막 행 한 개. */
    private static String renderNewItemRow(String shopId) {
        return "<tr class=\"shop-new-row\"><td colspan=\"5\">"
                + "<form hx-post=\"/admin/actions/shop-upsert-item\" "
                + "hx-target=\"#shop-result\" hx-swap=\"innerHTML\" class=\"shop-row-form\">"
                + "<input type=\"hidden\" name=\"shopId\" value=\"" + Html.esc(shopId) + "\">"
                + "<input type=\"text\" name=\"itemId\" required placeholder=\"itemId (예: red_potion)\">"
                + "<input type=\"number\" name=\"price\" required min=\"1\" placeholder=\"가격\">"
                + "<input type=\"number\" name=\"stockPerTx\" required min=\"1\" value=\"1\">"
                + "<input type=\"number\" name=\"sortOrder\" value=\"99\">"
                + "<button type=\"submit\">추가</button>"
                + "</form>"
                + "</td></tr>";
    }

}
