package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;
import mygame.game.entity.MonsterTemplate;

/**
 * 몬스터 템플릿 부모 행 추가/수정. drop table 은 별도 명령(DropReplace) 으로.
 */
public final class MonsterUpsertCommand implements AdminCommand {

    private final AdminFacade facade;
    private final MonsterTemplate template;

    public MonsterUpsertCommand(AdminFacade facade, MonsterTemplate template) {
        this.facade = facade;
        this.template = template;
    }

    @Override
    public String name() { return "MONSTER_UPSERT"; }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        int updated = facade.upsertMonsterTemplate(template);
        String payload = "{\"monsterId\":\"" + esc(template.id())
                + "\",\"maxHp\":" + template.maxHp()
                + ",\"expReward\":" + template.expReward()
                + ",\"updated\":" + updated + "}";
        audit.append(actor.adminId(), actor.username(), name(), payload);
        return updated > 0 ? "몬스터 적용: " + template.id() : "변경 없음";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
