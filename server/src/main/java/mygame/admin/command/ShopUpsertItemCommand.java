package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/**
 * 상점 카탈로그의 한 아이템 라인을 추가/수정. 영속화 직후 인메모리 캐시 reload —
 * 다음 SHOP_OPEN 부터 새 가격이 보인다.
 *
 * <p>audit payload 에는 shopId/itemId/price/stockPerTx/sortOrder/updated 모두 기록 —
 * 운영 사고 분석에 충분한 단서.
 */
public final class ShopUpsertItemCommand implements AdminCommand {

    private final AdminFacade facade;
    private final String shopId;
    private final String itemId;
    private final long price;
    private final int stockPerTx;
    private final int sortOrder;

    public ShopUpsertItemCommand(AdminFacade facade,
                                 String shopId, String itemId,
                                 long price, int stockPerTx, int sortOrder) {
        this.facade = facade;
        this.shopId = shopId;
        this.itemId = itemId;
        this.price = price;
        this.stockPerTx = stockPerTx;
        this.sortOrder = sortOrder;
    }

    @Override
    public String name() {
        return "SHOP_UPSERT_ITEM";
    }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        int updated = facade.upsertShopItem(shopId, itemId, price, stockPerTx, sortOrder);
        String payload = "{\"shopId\":\"" + jsonEscape(shopId)
                + "\",\"itemId\":\"" + jsonEscape(itemId)
                + "\",\"price\":" + price
                + ",\"stockPerTx\":" + stockPerTx
                + ",\"sortOrder\":" + sortOrder
                + ",\"updated\":" + updated + "}";
        audit.append(actor.adminId(), actor.username(), name(), payload);
        return updated > 0
                ? "상점 아이템 적용: " + shopId + " / " + itemId + " (가격 " + price + ", 재고 " + stockPerTx + ")"
                : "변경 없음";
    }

    /** 식별자에 특수문자가 들어올 일은 거의 없지만 audit JSON 안전을 위한 최소 escape. */
    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
