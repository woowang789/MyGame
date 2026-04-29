package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/** 상점 카탈로그의 한 아이템 라인 삭제 + 캐시 reload. */
public final class ShopDeleteItemCommand implements AdminCommand {

    private final AdminFacade facade;
    private final String shopId;
    private final String itemId;

    public ShopDeleteItemCommand(AdminFacade facade, String shopId, String itemId) {
        this.facade = facade;
        this.shopId = shopId;
        this.itemId = itemId;
    }

    @Override
    public String name() {
        return "SHOP_DELETE_ITEM";
    }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        int deleted = facade.deleteShopItem(shopId, itemId);
        String payload = "{\"shopId\":\"" + esc(shopId)
                + "\",\"itemId\":\"" + esc(itemId)
                + "\",\"deleted\":" + deleted + "}";
        audit.append(actor.adminId(), actor.username(), name(), payload);
        return deleted > 0
                ? "상점 아이템 삭제: " + shopId + " / " + itemId
                : "대상 없음 (삭제할 라인이 없습니다)";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
