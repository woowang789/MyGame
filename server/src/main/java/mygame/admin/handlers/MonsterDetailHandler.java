package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Optional;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.view.Html;
import mygame.game.entity.MonsterTemplate;
import mygame.game.item.DropTable.Entry;

/**
 * GET /admin/monsters/{id} — 단일 몬스터 편집 페이지.
 * 부모 행 폼 + drop table 인라인 편집 + 새 drop 추가 + 삭제.
 */
public final class MonsterDetailHandler implements HttpHandler {

    private static final String PREFIX = "/admin/monsters/";

    private final AdminFacade facade;

    public MonsterDetailHandler(AdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith(PREFIX)) {
            HttpUtils.sendText(ex, 404, "Not Found");
            return;
        }
        String monsterId = path.substring(PREFIX.length());
        if (monsterId.isEmpty() || monsterId.contains("/")) {
            HttpUtils.sendText(ex, 400, "잘못된 monsterId");
            return;
        }
        Optional<MonsterTemplate> opt = facade.monsterTemplate(monsterId);
        if (opt.isEmpty()) {
            HttpUtils.sendHtml(ex, 404, Html.layout("Monster not found",
                    "<h1>몬스터 없음</h1><p>id=" + Html.esc(monsterId) + " 인 몬스터를 찾지 못했습니다.</p>"));
            return;
        }
        MonsterTemplate m = opt.get();

        StringBuilder sb = new StringBuilder();
        sb.append("<p><a href=\"/admin/monsters\">← 몬스터 목록</a></p>");
        sb.append("<h1>").append(Html.esc(m.displayName()))
          .append(" <span class=\"muted\">").append(Html.esc(m.id())).append("</span></h1>");

        sb.append("<section><h2>편집</h2>");
        sb.append(renderEditForm(m));
        sb.append("<p id=\"monster-result\" class=\"action-result\"></p>");
        sb.append("</section>");

        sb.append("<section><h2>드롭 테이블</h2>");
        sb.append("<table class=\"data-table\" id=\"monster-drops\"><thead><tr>")
          .append("<th>itemId</th><th>chance (0..1)</th><th>순서</th><th>액션</th>")
          .append("</tr></thead><tbody>");
        int order = 0;
        for (Entry e : m.dropTable().entries()) {
            sb.append(renderDropRow(m.id(), e.itemId(), e.chance(), order));
            order++;
        }
        sb.append(renderNewDropRow(m.id()));
        sb.append("</tbody></table>");
        sb.append("<p id=\"drop-result\" class=\"action-result\"></p>");
        sb.append("</section>");

        sb.append("<section><h2>삭제</h2>")
          .append("<p class=\"empty\">monster_drops 도 FK CASCADE 로 함께 삭제됩니다. SpawnPoint 정합성은 서버 재시작 시 검증.</p>")
          .append("<form hx-post=\"/admin/actions/monster-delete\" hx-target=\"#monster-result\" hx-swap=\"innerHTML\" ")
          .append("hx-confirm=\"" + Html.esc(m.id()) + " 몬스터를 삭제합니다. 진행할까요?\" class=\"row-action\">")
          .append("<input type=\"hidden\" name=\"monsterId\" value=\"").append(Html.esc(m.id())).append("\">")
          .append("<button type=\"submit\">삭제</button>")
          .append("</form>")
          .append("</section>");

        HttpUtils.sendHtml(ex, 200, Html.layout("Monster " + m.id(), sb.toString()));
    }

    /** 편집/생성 공통 폼. {@code m} null 이면 새 몬스터 생성. */
    static String renderEditForm(MonsterTemplate m) {
        boolean isNew = (m == null);
        StringBuilder sb = new StringBuilder();
        sb.append("<form hx-post=\"/admin/actions/monster-upsert\" hx-target=\"#monster-result\" hx-swap=\"innerHTML\" class=\"item-form\">");
        if (isNew) {
            sb.append(field("id", "text", "", "예: blue_snail", true));
        } else {
            sb.append(hidden("id", m.id()));
            sb.append(displayRow("id", m.id() + " <span class=\"muted\">(고정)</span>"));
        }
        sb.append(field("display_name", "text", isNew ? "" : m.displayName(), "표시 이름", true));
        sb.append(field("max_hp", "number", isNew ? "100" : String.valueOf(m.maxHp()), "최대 HP", true));
        sb.append(field("attack_damage", "number", isNew ? "10" : String.valueOf(m.attackDamage()), "접촉 피해", true));
        sb.append(field("attack_interval_ms", "number", isNew ? "1500" : String.valueOf(m.attackIntervalMs()), "공격 주기 ms", true));
        sb.append(field("speed", "number", isNew ? "60" : String.valueOf(m.speed()), "이동 속도", true));
        sb.append(field("exp_reward", "number", isNew ? "10" : String.valueOf(m.expReward()), "처치 EXP", true));
        sb.append(field("respawn_delay_ms", "number", isNew ? "5000" : String.valueOf(m.respawnDelayMs()), "리스폰 ms", true));
        sb.append(field("meso_min", "number", isNew ? "1" : String.valueOf(m.mesoMin()), "메소 최소", true));
        sb.append(field("meso_max", "number", isNew ? "10" : String.valueOf(m.mesoMax()), "메소 최대", true));
        sb.append(field("body_color", "text", isNew ? "0xffffff" : String.format("0x%06x", m.bodyColor()), "0xRRGGBB", true));
        sb.append("<button type=\"submit\">").append(isNew ? "추가" : "저장").append("</button>");
        sb.append("</form>");
        return sb.toString();
    }

    private static String renderDropRow(String monsterId, String itemId, double chance, int sortOrder) {
        String rowId = "drop-row-" + safe(monsterId + "-" + itemId);
        StringBuilder sb = new StringBuilder();
        sb.append("<tr id=\"").append(rowId).append("\">")
          .append("<td><code>").append(Html.esc(itemId)).append("</code></td>")
          .append("<td colspan=\"2\">")
          .append("<form hx-post=\"/admin/actions/monster-drop-upsert\" ")
          .append("hx-target=\"#drop-result\" hx-swap=\"innerHTML\" class=\"shop-row-form\">")
          .append("<input type=\"hidden\" name=\"monsterId\" value=\"").append(Html.esc(monsterId)).append("\">")
          .append("<input type=\"hidden\" name=\"itemId\" value=\"").append(Html.esc(itemId)).append("\">")
          .append("<input type=\"number\" name=\"chance\" value=\"").append(chance).append("\" required min=\"0\" max=\"1\" step=\"0.01\">")
          .append("<input type=\"number\" name=\"sortOrder\" value=\"").append(sortOrder).append("\" required step=\"1\">")
          .append("<button type=\"submit\">저장</button>")
          .append("</form>")
          .append("</td>")
          .append("<td>")
          .append("<form hx-post=\"/admin/actions/monster-drop-delete\" hx-target=\"#drop-result\" hx-swap=\"innerHTML\" ")
          .append("hx-confirm=\"" + Html.esc(itemId) + " drop 라인을 삭제합니다.\" class=\"row-action\">")
          .append("<input type=\"hidden\" name=\"monsterId\" value=\"").append(Html.esc(monsterId)).append("\">")
          .append("<input type=\"hidden\" name=\"itemId\" value=\"").append(Html.esc(itemId)).append("\">")
          .append("<button type=\"submit\">삭제</button>")
          .append("</form>")
          .append("</td>")
          .append("</tr>");
        return sb.toString();
    }

    private static String renderNewDropRow(String monsterId) {
        return "<tr class=\"shop-new-row\"><td colspan=\"4\">"
                + "<form hx-post=\"/admin/actions/monster-drop-upsert\" "
                + "hx-target=\"#drop-result\" hx-swap=\"innerHTML\" class=\"shop-row-form\">"
                + "<input type=\"hidden\" name=\"monsterId\" value=\"" + Html.esc(monsterId) + "\">"
                + "<input type=\"text\" name=\"itemId\" required placeholder=\"itemId (예: red_potion)\">"
                + "<input type=\"number\" name=\"chance\" required min=\"0\" max=\"1\" step=\"0.01\" placeholder=\"확률 0..1\">"
                + "<input type=\"number\" name=\"sortOrder\" value=\"99\">"
                + "<button type=\"submit\">추가</button>"
                + "</form>"
                + "</td></tr>";
    }

    // --- 작은 폼 helper (ItemDetailHandler 와 일관) ---

    private static String field(String name, String type, String value, String hint, boolean required) {
        return "<label class=\"item-field\"><span>" + Html.esc(name) + "</span>"
                + "<input type=\"" + type + "\" name=\"" + name + "\""
                + (required ? " required" : "")
                + " value=\"" + Html.esc(value) + "\""
                + " placeholder=\"" + Html.esc(hint) + "\""
                + "></label>";
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + name + "\" value=\"" + Html.esc(value) + "\">";
    }

    private static String displayRow(String name, String html) {
        return "<label class=\"item-field\"><span>" + Html.esc(name) + "</span><output>" + html + "</output></label>";
    }

    private static String safe(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        return sb.toString();
    }
}
