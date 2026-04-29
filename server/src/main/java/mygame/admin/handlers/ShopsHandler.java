package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.view.Html;
import mygame.db.ShopRepository.ShopSummary;

/** GET /admin/shops — 모든 상점 목록과 아이템 수를 한눈에. */
public final class ShopsHandler implements HttpHandler {

    private final AdminFacade facade;

    public ShopsHandler(AdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        List<ShopSummary> shops = facade.shopList();
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>상점 목록</h1>");
        sb.append("<p class=\"empty\">가격/재고 변경은 즉시 반영됩니다 (캐시 reload). 새 상점 생성은 현재 미지원 — 마이그레이션으로 시드.</p>");
        if (shops.isEmpty()) {
            sb.append("<p class=\"empty\">등록된 상점이 없습니다.</p>");
        } else {
            sb.append("<table class=\"data-table\"><thead><tr>")
              .append("<th>ID</th><th>이름</th><th>아이템 수</th>")
              .append("</tr></thead><tbody>");
            for (ShopSummary s : shops) {
                sb.append("<tr>")
                  .append("<td><a href=\"/admin/shops/").append(Html.esc(s.id())).append("\">")
                  .append(Html.esc(s.id())).append("</a></td>")
                  .append("<td>").append(Html.esc(s.name())).append("</td>")
                  .append("<td>").append(s.itemCount()).append("</td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table>");
        }
        HttpUtils.sendHtml(ex, 200, Html.layout("Shops", sb.toString()));
    }
}
