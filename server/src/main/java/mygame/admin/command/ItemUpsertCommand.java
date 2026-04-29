package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;
import mygame.game.item.ItemTemplate;

/**
 * 아이템 템플릿 추가/수정 명령. 영속화 직후 ItemRegistry 캐시 reload 가 자동으로 일어나
 * 다음 ItemRegistry.get 호출은 새 값을 본다.
 *
 * <p>ItemTemplate 의 invariants(EQUIPMENT 는 slot/bonus 필수, CONSUMABLE 은 use 필수 등)
 * 는 record ctor 가 검증하므로 본 명령은 단순 위임에 집중.
 */
public final class ItemUpsertCommand implements AdminCommand {

    private final AdminFacade facade;
    private final ItemTemplate template;

    public ItemUpsertCommand(AdminFacade facade, ItemTemplate template) {
        this.facade = facade;
        this.template = template;
    }

    @Override
    public String name() {
        return "ITEM_UPSERT";
    }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        int updated = facade.upsertItemTemplate(template);
        // payload: 운영 사고 분석 충분 + 비대화 방지(name 만 짧게).
        String payload = "{\"itemId\":\"" + esc(template.id())
                + "\",\"type\":\"" + template.type().name() + "\""
                + ",\"sellPrice\":" + template.sellPrice()
                + ",\"updated\":" + updated + "}";
        audit.append(actor.adminId(), actor.username(), name(), payload);
        return updated > 0
                ? "아이템 적용: " + template.id() + " (" + template.type() + ")"
                : "변경 없음";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
