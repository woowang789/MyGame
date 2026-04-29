package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.view.Html;
import mygame.game.entity.MonsterTemplate;

/** GET /admin/monsters — 모든 몬스터 종 목록. */
public final class MonstersHandler implements HttpHandler {

    private final AdminFacade facade;

    public MonstersHandler(AdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        List<MonsterTemplate> monsters = facade.monsterTemplates();
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>몬스터 템플릿</h1>");
        sb.append("<p class=\"empty\">변경은 즉시 MonsterRegistry 캐시 reload 로 반영. ")
          .append("이미 스폰된 몬스터의 인스턴스는 다음 리스폰부터 새 스펙을 따른다.</p>");
        if (monsters.isEmpty()) {
            sb.append("<p class=\"empty\">등록된 몬스터가 없습니다.</p>");
        } else {
            sb.append("<table class=\"data-table\"><thead><tr>")
              .append("<th>id</th><th>이름</th><th>HP</th><th>ATK</th>")
              .append("<th>EXP</th><th>메소</th><th>drop 수</th><th>편집</th>")
              .append("</tr></thead><tbody>");
            for (MonsterTemplate m : monsters) {
                sb.append("<tr>")
                  .append("<td><code>").append(Html.esc(m.id())).append("</code></td>")
                  .append("<td>").append(Html.esc(m.displayName())).append("</td>")
                  .append("<td>").append(m.maxHp()).append("</td>")
                  .append("<td>").append(m.attackDamage()).append("</td>")
                  .append("<td>").append(m.expReward()).append("</td>")
                  .append("<td>").append(m.mesoMin()).append("~").append(m.mesoMax()).append("</td>")
                  .append("<td>").append(m.dropTable().entries().size()).append("</td>")
                  .append("<td><a href=\"/admin/monsters/").append(Html.esc(m.id())).append("\">편집</a></td>")
                  .append("</tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("<section><h2>새 몬스터 추가</h2>")
          .append(MonsterDetailHandler.renderEditForm(null))
          .append("<p id=\"monster-result\" class=\"action-result\"></p>")
          .append("</section>");
        HttpUtils.sendHtml(ex, 200, Html.layout("Monsters", sb.toString()));
    }
}
