package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/**
 * 몬스터 템플릿 삭제. monster_drops 는 FK CASCADE 로 함께 삭제됨.
 *
 * <p>SpawnPoint 가 코드 상수라 World 시작 시점에 정합성 검증되므로 여기서는 단순 삭제 +
 * audit 기록.
 */
public final class MonsterDeleteCommand implements AdminCommand {

    private final AdminFacade facade;
    private final String monsterId;

    public MonsterDeleteCommand(AdminFacade facade, String monsterId) {
        this.facade = facade;
        this.monsterId = monsterId;
    }

    @Override public String name() { return "MONSTER_DELETE"; }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        int deleted = facade.deleteMonsterTemplate(monsterId);
        String payload = "{\"monsterId\":\"" + esc(monsterId) + "\",\"deleted\":" + deleted + "}";
        audit.append(actor.adminId(), actor.username(), name(), payload);
        return deleted > 0
                ? "삭제 완료: " + monsterId
                : "대상 없음 (id=" + monsterId + ")";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
