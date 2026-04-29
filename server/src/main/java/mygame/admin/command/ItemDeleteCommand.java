package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.AdminFacade.ItemDeleteResult;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/**
 * 아이템 템플릿 삭제. shop_items 가 참조 중이면 차단되어 audit 에는 차단 사유와 함께 기록.
 */
public final class ItemDeleteCommand implements AdminCommand {

    private final AdminFacade facade;
    private final String itemId;

    public ItemDeleteCommand(AdminFacade facade, String itemId) {
        this.facade = facade;
        this.itemId = itemId;
    }

    @Override
    public String name() {
        return "ITEM_DELETE";
    }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        ItemDeleteResult r = facade.deleteItemTemplate(itemId);
        String payload = "{\"itemId\":\"" + esc(itemId) + "\""
                + ",\"deleted\":" + r.deleted()
                + ",\"shopReferences\":" + r.shopReferences() + "}";
        audit.append(actor.adminId(), actor.username(), name(), payload);
        if (r.deleted()) return "삭제 완료: " + itemId;
        if (r.shopReferences() > 0) {
            return "차단: " + r.shopReferences() + " 개의 shop_items 가 참조 중. 먼저 상점에서 제거하세요.";
        }
        return "대상 없음 (id=" + itemId + ")";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
