package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/** 몬스터 drop table 의 한 라인 삭제. */
public final class MonsterDropDeleteCommand implements AdminCommand {

    private final AdminFacade facade;
    private final String monsterId;
    private final String itemId;

    public MonsterDropDeleteCommand(AdminFacade facade, String monsterId, String itemId) {
        this.facade = facade;
        this.monsterId = monsterId;
        this.itemId = itemId;
    }

    @Override public String name() { return "MONSTER_DROP_DELETE"; }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        int deleted = facade.deleteMonsterDropLine(monsterId, itemId);
        String payload = "{\"monsterId\":\"" + esc(monsterId)
                + "\",\"itemId\":\"" + esc(itemId)
                + "\",\"deleted\":" + deleted + "}";
        audit.append(actor.adminId(), actor.username(), name(), payload);
        return deleted > 0
                ? "drop 삭제: " + monsterId + " / " + itemId
                : "대상 없음";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
