package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/**
 * 모든 접속 플레이어 상태를 즉시 DB 에 저장하는 명령.
 *
 * <p>{@code PeriodicSaver.saveAll()} 을 그대로 위임 — 단일 스레드 직렬 실행 보장이
 * 이미 들어 있어 race 안전.
 */
public final class ForceSaveCommand implements AdminCommand {

    private final AdminFacade facade;

    public ForceSaveCommand(AdminFacade facade) {
        this.facade = facade;
    }

    @Override
    public String name() {
        return "FORCE_SAVE";
    }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        int onlineBefore = facade.onlineCount();
        facade.forceSaveAll();
        String payload = "{\"onlineCount\":" + onlineBefore + "}";
        audit.append(actor.adminId(), actor.username(), name(), payload);
        return "강제 저장 완료 — 대상 " + onlineBefore + " 명";
    }
}
