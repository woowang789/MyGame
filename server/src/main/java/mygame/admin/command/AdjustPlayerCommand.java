package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.AdminFacade.AdjustResult;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/**
 * 플레이어 메소 또는 EXP 보정 명령. 한 클래스가 두 종류(meso/exp) 를 모두 표현 —
 * 분기는 {@link Kind} 로만, 도메인 호출과 audit action 키워드만 다르게 흐른다.
 *
 * <p>구조 메모: SetAccountDisabledCommand 와 같은 패턴. AdminCommand 인터페이스가
 * 매개변수 없는 execute 시그니처라 명령 인스턴스 생성 시점에 (대상, 종류, 값) 을 캡처.
 */
public final class AdjustPlayerCommand implements AdminCommand {

    public enum Kind {
        MESO("ADJUST_MESO"),
        EXP("ADJUST_EXP");

        final String auditAction;

        Kind(String auditAction) { this.auditAction = auditAction; }
    }

    private final AdminFacade facade;
    private final long accountId;
    private final Kind kind;
    private final long delta;

    public AdjustPlayerCommand(AdminFacade facade, long accountId, Kind kind, long delta) {
        this.facade = facade;
        this.accountId = accountId;
        this.kind = kind;
        this.delta = delta;
    }

    @Override
    public String name() {
        return kind.auditAction;
    }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        AdjustResult result = switch (kind) {
            case MESO -> facade.adjustMeso(accountId, delta);
            case EXP -> facade.adjustExp(accountId, (int) delta);
        };

        // payload 는 운영 사고 분석에 충분한 형태로 — 실패 케이스도 반드시 audit 에 남긴다.
        StringBuilder payload = new StringBuilder()
                .append("{\"accountId\":").append(accountId)
                .append(",\"delta\":").append(delta)
                .append(",\"playerExists\":").append(result.playerExists())
                .append(",\"wasOnline\":").append(result.wasOnline())
                .append(",\"newValue\":").append(result.newValue())
                .append("}");
        audit.append(actor.adminId(), actor.username(), name(), payload.toString());

        if (!result.playerExists()) {
            return "대상 캐릭터 없음 (account=" + accountId + ")";
        }
        String label = kind == Kind.MESO ? "메소" : "EXP";
        String mode = result.wasOnline() ? "온라인" : "오프라인 DB";
        return label + " 적용(" + mode + ") — 새 값: " + result.newValue();
    }
}
