package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Optional;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.view.FormRenderer;
import mygame.admin.view.Html;
import mygame.game.item.EquipSlot;
import mygame.game.item.ItemTemplate;
import mygame.game.item.ItemTemplate.ItemType;
import mygame.game.stat.Stats;

/**
 * GET /admin/items/{id} — 단일 아이템 편집 페이지. 새 아이템 추가는 ItemsHandler 가 같은
 * 폼을 빈 값으로 노출.
 *
 * <p>핵심 디자인: 한 거대 폼이 모든 필드를 노출. type 별로 의미 있는 컬럼만 채우면 되고,
 * 서버측 ItemTemplate ctor 가 invariants(EQUIPMENT 는 slot/bonus 필수 등) 를 강제한다.
 * 클라 측 자바스크립트로 type 변경 시 필드 enable/disable 을 토글하는 건 추후 개선.
 */
public final class ItemDetailHandler implements HttpHandler {

    private static final String PREFIX = "/admin/items/";

    private final AdminFacade facade;

    public ItemDetailHandler(AdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith(PREFIX)) {
            HttpUtils.sendText(ex, 404, "Not Found");
            return;
        }
        String itemId = path.substring(PREFIX.length());
        if (itemId.isEmpty() || itemId.contains("/")) {
            HttpUtils.sendText(ex, 400, "잘못된 itemId");
            return;
        }
        Optional<ItemTemplate> opt = facade.itemTemplate(itemId);
        if (opt.isEmpty()) {
            HttpUtils.sendHtml(ex, 404, Html.layout("Item not found",
                    "<h1>아이템 없음</h1><p>id=" + Html.esc(itemId) + " 인 아이템을 찾지 못했습니다.</p>"));
            return;
        }
        ItemTemplate t = opt.get();

        StringBuilder sb = new StringBuilder();
        sb.append("<p><a href=\"/admin/items\">← 아이템 목록</a></p>");
        sb.append("<h1>").append(Html.esc(t.name()))
          .append(" <span class=\"muted\">").append(Html.esc(t.id())).append("</span></h1>");

        sb.append("<section><h2>편집</h2>");
        sb.append(renderEditForm(t));
        sb.append("<p id=\"item-result\" class=\"action-result\"></p>");
        sb.append("</section>");

        sb.append("<section><h2>삭제</h2>")
          .append("<p class=\"empty\">참조하는 shop_items 가 있으면 차단됩니다 — 운영 사고 방지.</p>")
          .append("<form hx-post=\"/admin/actions/item-delete\" hx-target=\"#item-result\" hx-swap=\"innerHTML\" ")
          .append("hx-confirm=\"" + Html.esc(t.id()) + " 아이템을 삭제합니다. 진행할까요?\" class=\"row-action\">")
          .append("<input type=\"hidden\" name=\"itemId\" value=\"").append(Html.esc(t.id())).append("\">")
          .append("<button type=\"submit\">삭제</button>")
          .append("</form>")
          .append("</section>");

        HttpUtils.sendHtml(ex, 200, Html.layout("Item " + t.id(), sb.toString()));
    }

    /**
     * 편집/생성 공통 폼. {@code t} 가 null 이면 새 아이템 생성 폼.
     */
    static String renderEditForm(ItemTemplate t) {
        boolean isNew = (t == null);
        Stats b = (!isNew && t.bonus() != null) ? t.bonus() : new Stats(0, 0, 0, 0);
        int useHeal = (!isNew && t.use() != null) ? t.use().heal() : 0;
        int useManaHeal = (!isNew && t.use() != null) ? t.use().manaHeal() : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("<form hx-post=\"/admin/actions/item-upsert\" hx-target=\"#item-result\" hx-swap=\"innerHTML\" class=\"item-form\">");
        // id 는 새 아이템일 때만 편집 가능 — 변경 시 외래 데이터(인벤토리·드롭 테이블)와의 정합성 깨짐 방지
        if (isNew) {
            sb.append(FormRenderer.field("id", "text", "", "예: red_potion (영숫자_- 권장)", true, "data-new=\"1\""));
        } else {
            sb.append(FormRenderer.hidden("id", t.id()));
            sb.append(FormRenderer.displayRow("id", Html.esc(t.id()) + " <span class=\"muted\">(고정)</span>"));
        }
        sb.append(FormRenderer.field("name", "text", isNew ? "" : t.name(), "표시 이름", true));
        sb.append(FormRenderer.field("color", "text", isNew ? "0xffffff" : String.format("0x%06x", t.color()),
                "0xRRGGBB", true));
        sb.append(FormRenderer.select("type", "type", enumNames(ItemType.values()),
                isNew ? null : t.type().name(), true, null));
        sb.append(FormRenderer.select("slot", "slot", enumNames(EquipSlot.values()),
                isNew || t.slot() == null ? "" : t.slot().name(), false, "(없음)"));
        // 장비 보너스
        sb.append(FormRenderer.field("bonus_max_hp", "number", String.valueOf(b.maxHp()), "EQUIPMENT 일 때 maxHp 보너스", false));
        sb.append(FormRenderer.field("bonus_max_mp", "number", String.valueOf(b.maxMp()), "maxMp", false));
        sb.append(FormRenderer.field("bonus_attack", "number", String.valueOf(b.attack()), "attack", false));
        sb.append(FormRenderer.field("bonus_speed", "number", String.valueOf(b.speed()), "speed", false));
        // 소비 효과
        sb.append(FormRenderer.field("use_heal", "number", String.valueOf(useHeal), "CONSUMABLE 회복량", false));
        sb.append(FormRenderer.field("use_mana_heal", "number", String.valueOf(useManaHeal), "MP 회복량", false));
        sb.append(FormRenderer.field("sell_price", "number", isNew ? "0" : String.valueOf(t.sellPrice()), "0=매입 불가", true));
        sb.append("<button type=\"submit\">").append(isNew ? "추가" : "저장").append("</button>");
        sb.append("</form>");
        return sb.toString();
    }

    private static String[] enumNames(Enum<?>[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i].name();
        return out;
    }
}
