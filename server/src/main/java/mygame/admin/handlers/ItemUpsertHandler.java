package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.ItemUpsertCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import mygame.game.item.EquipSlot;
import mygame.game.item.ItemTemplate;
import mygame.game.item.ItemTemplate.ItemType;
import mygame.game.item.ItemTemplate.UseEffect;
import mygame.game.stat.Stats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /admin/actions/item-upsert — form 의 모든 필드를 받아 ItemTemplate 으로 빌드.
 *
 * <p>type 별로 의미 없는 필드는 생성 시점에 무시한다 — record ctor 가 invariants 를 강제.
 * 잘못된 조합(EQUIPMENT 인데 slot 누락, CONSUMABLE 인데 use_* 0 둘 다) 은 IAE 로 떨어져
 * 친화적 메시지로 변환.
 */
public final class ItemUpsertHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ItemUpsertHandler.class);

    private final AdminFacade facade;
    private final AuditLogRepository audit;

    public ItemUpsertHandler(AdminFacade facade, AuditLogRepository audit) {
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
            ItemTemplate t = buildFromForm(form);
            String message = new ItemUpsertCommand(facade, t).execute(AuthFilter.sessionOf(ex), audit);
            HttpUtils.sendHtml(ex, 200, "<span class=\"ok\">" + Html.esc(message) + "</span>");
        } catch (IllegalArgumentException iae) {
            // ItemTemplate ctor 의 invariants 위반은 사용자가 고칠 수 있는 입력 오류.
            HttpUtils.sendHtml(ex, 400, "<span class=\"error\">" + Html.esc(iae.getMessage()) + "</span>");
        } catch (Exception e) {
            log.error("아이템 upsert 실패", e);
            HttpUtils.sendHtml(ex, 500, "<span class=\"error\">내부 오류</span>");
        }
    }

    private static ItemTemplate buildFromForm(Map<String, String> form) {
        String id = form.getOrDefault("id", "").trim();
        String name = form.getOrDefault("name", "").trim();
        if (id.isEmpty() || name.isEmpty()) {
            throw new IllegalArgumentException("id / name 은 비어있을 수 없습니다.");
        }
        int color = parseColor(form.getOrDefault("color", "0xffffff"));
        ItemType type;
        try {
            type = ItemType.valueOf(form.getOrDefault("type", "").trim());
        } catch (IllegalArgumentException ie) {
            throw new IllegalArgumentException("type 은 CONSUMABLE/EQUIPMENT/ETC 중 하나여야 합니다.");
        }
        long sellPrice = parseLong(form.get("sell_price"), 0L);
        if (sellPrice < 0) throw new IllegalArgumentException("sell_price 는 0 이상이어야 합니다.");

        return switch (type) {
            case EQUIPMENT -> {
                String slotStr = form.getOrDefault("slot", "").trim();
                if (slotStr.isEmpty()) {
                    throw new IllegalArgumentException("EQUIPMENT 는 slot 이 필수입니다.");
                }
                EquipSlot slot;
                try { slot = EquipSlot.valueOf(slotStr); }
                catch (IllegalArgumentException ie) {
                    throw new IllegalArgumentException("slot 값이 올바르지 않습니다: " + slotStr);
                }
                Stats bonus = new Stats(
                        parseInt(form.get("bonus_max_hp"), 0),
                        parseInt(form.get("bonus_max_mp"), 0),
                        parseInt(form.get("bonus_attack"), 0),
                        parseInt(form.get("bonus_speed"), 0));
                yield new ItemTemplate(id, name, color, type, slot, bonus, sellPrice);
            }
            case CONSUMABLE -> {
                UseEffect use = new UseEffect(
                        parseInt(form.get("use_heal"), 0),
                        parseInt(form.get("use_mana_heal"), 0));
                yield new ItemTemplate(id, name, color, type, use, sellPrice);
            }
            case ETC -> new ItemTemplate(id, name, color, type, sellPrice);
        };
    }

    /** "0xRRGGBB" 또는 "16776960" 또는 "#RRGGBB" 모두 허용. */
    private static int parseColor(String s) {
        s = s.trim();
        if (s.startsWith("#")) s = "0x" + s.substring(1);
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Integer.parseUnsignedInt(s.substring(2), 16);
        }
        return Integer.parseInt(s);
    }

    private static int parseInt(String s, int dflt) {
        if (s == null || s.isBlank()) return dflt;
        return Integer.parseInt(s.trim());
    }

    private static long parseLong(String s, long dflt) {
        if (s == null || s.isBlank()) return dflt;
        return Long.parseLong(s.trim());
    }
}
