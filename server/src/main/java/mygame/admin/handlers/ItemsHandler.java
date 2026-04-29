package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.view.Html;
import mygame.game.item.ItemTemplate;

/**
 * GET /admin/items — 모든 아이템 템플릿 목록.
 *
 * <p>편집 페이지로 가는 링크와, 새 아이템 추가 폼이 포함된다.
 */
public final class ItemsHandler implements HttpHandler {

    private final AdminFacade facade;

    public ItemsHandler(AdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        List<ItemTemplate> items = facade.itemTemplates();
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>아이템 템플릿</h1>");
        sb.append("<p class=\"empty\">변경은 즉시 ItemRegistry 캐시 reload 로 반영. ")
          .append("ETC 는 매입가만, CONSUMABLE 은 use_*, EQUIPMENT 는 slot+bonus_* 가 의미.</p>");
        if (items.isEmpty()) {
            sb.append("<p class=\"empty\">등록된 아이템이 없습니다.</p>");
        } else {
            sb.append("<table class=\"data-table\"><thead><tr>")
              .append("<th>id</th><th>이름</th><th>type</th><th>slot</th>")
              .append("<th>매입가</th><th>편집</th>")
              .append("</tr></thead><tbody>");
            for (ItemTemplate t : items) {
                sb.append("<tr>")
                  .append("<td><code>").append(Html.esc(t.id())).append("</code></td>")
                  .append("<td>").append(Html.esc(t.name())).append("</td>")
                  .append("<td>").append(t.type().name()).append("</td>")
                  .append("<td>").append(t.slot() == null ? "-" : t.slot().name()).append("</td>")
                  .append("<td>").append(t.sellPrice()).append("</td>")
                  .append("<td><a href=\"/admin/items/").append(Html.esc(t.id())).append("\">편집</a></td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("<section><h2>새 아이템 추가</h2>")
          .append(ItemDetailHandler.renderEditForm(null))
          .append("<p id=\"item-result\" class=\"action-result\"></p>")
          .append("</section>");

        HttpUtils.sendHtml(ex, 200, Html.layout("Items", sb.toString()));
    }
}
