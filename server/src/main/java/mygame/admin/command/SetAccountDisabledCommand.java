package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/**
 * 계정 정지/해제 명령. 한 클래스가 두 액션(BAN/UNBAN) 을 동시에 표현 — disabled
 * 값이 곧 의도이고, audit 액션 키워드만 분기.
 *
 * <p>구조 메모: AdminCommand 인터페이스가 매개변수 없는 execute 시그니처라서, 본 명령은
 * 생성 시점에 (accountId, disabled) 를 캡처한다. 핸들러가 요청별로 새 인스턴스를 만든다.
 */
public final class SetAccountDisabledCommand implements AdminCommand {

    private final AdminFacade facade;
    private final long accountId;
    private final boolean disabled;

    public SetAccountDisabledCommand(AdminFacade facade, long accountId, boolean disabled) {
        this.facade = facade;
        this.accountId = accountId;
        this.disabled = disabled;
    }

    @Override
    public String name() {
        return disabled ? "ACCOUNT_BAN" : "ACCOUNT_UNBAN";
    }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        int updated = facade.setAccountDisabled(accountId, disabled);
        if (updated == 0) {
            // audit 도 기록은 하되 결과를 명시 — 잘못된 id 호출 흔적도 보존.
            audit.append(actor.adminId(), actor.username(), name(),
                    "{\"accountId\":" + accountId + ",\"updated\":0}");
            return "대상 계정 없음 (id=" + accountId + ")";
        }
        audit.append(actor.adminId(), actor.username(), name(),
                "{\"accountId\":" + accountId + ",\"disabled\":" + disabled + "}");
        return (disabled ? "정지 완료" : "해제 완료") + " (id=" + accountId + ")";
    }
}
