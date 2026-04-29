package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/** 몬스터 drop table 의 한 라인 추가/수정. */
public final class MonsterDropUpsertCommand implements AdminCommand {

    private final AdminFacade facade;
    private final String monsterId;
    private final String itemId;
    private final double chance;
    private final int sortOrder;

    public MonsterDropUpsertCommand(AdminFacade facade, String monsterId, String itemId,
                                    double chance, int sortOrder) {
        this.facade = facade;
        this.monsterId = monsterId;
        this.itemId = itemId;
        this.chance = chance;
        this.sortOrder = sortOrder;
    }

    @Override public String name() { return "MONSTER_DROP_UPSERT"; }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        int updated = facade.upsertMonsterDropLine(monsterId, itemId, chance, sortOrder);
        String payload = "{\"monsterId\":\"" + esc(monsterId)
                + "\",\"itemId\":\"" + esc(itemId)
                + "\",\"chance\":" + chance
                + ",\"sortOrder\":" + sortOrder
                + ",\"updated\":" + updated + "}";
        audit.append(actor.adminId(), actor.username(), name(), payload);
        return updated > 0
                ? "drop 적용: " + monsterId + " / " + itemId + " @ " + chance
                : "변경 없음";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
