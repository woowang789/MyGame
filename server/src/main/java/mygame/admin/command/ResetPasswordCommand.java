package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/**
 * 계정 비밀번호 리셋 명령.
 *
 * <p>보안 핵심: <em>audit payload 에 비밀번호를 절대 담지 않는다</em>. accountId 와 결과만
 * 기록한다. 비밀번호 길이도 기록하지 않는다 — brute-force 시 후보 공간 축소 단서가 됨.
 *
 * <p>실패 케이스(존재하지 않는 id)도 audit 에는 남긴다 — 잘못된 호출 흔적도 보존.
 */
public final class ResetPasswordCommand implements AdminCommand {

    private final AdminFacade facade;
    private final long accountId;
    private final String newRawPassword;

    public ResetPasswordCommand(AdminFacade facade, long accountId, String newRawPassword) {
        this.facade = facade;
        this.accountId = accountId;
        this.newRawPassword = newRawPassword;
    }

    @Override
    public String name() {
        return "ACCOUNT_PASSWORD_RESET";
    }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        int updated = facade.resetPassword(accountId, newRawPassword);
        // payload 에 비밀번호·길이 모두 미포함 — 의도적.
        audit.append(actor.adminId(), actor.username(), name(),
                "{\"accountId\":" + accountId + ",\"updated\":" + updated + "}");
        if (updated == 0) {
            return "대상 계정 없음 (id=" + accountId + ")";
        }
        return "비밀번호 리셋 완료 (id=" + accountId + ")";
    }
}
