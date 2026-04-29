package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.AdminFacade.KickResult;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/**
 * 접속 중 플레이어 강제 퇴장 명령. 결과 상태(NO_PLAYER / NOT_ONLINE / KICKED) 모두를
 * audit 에 기록 — 실패 케이스 흔적도 보존.
 */
public final class KickPlayerCommand implements AdminCommand {

    private final AdminFacade facade;
    private final long accountId;

    public KickPlayerCommand(AdminFacade facade, long accountId) {
        this.facade = facade;
        this.accountId = accountId;
    }

    @Override
    public String name() {
        return "PLAYER_KICK";
    }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        KickResult result = facade.kickPlayer(accountId);
        StringBuilder payload = new StringBuilder()
                .append("{\"accountId\":").append(accountId)
                .append(",\"state\":\"").append(result.state().name()).append("\"");
        if (result.playerName() != null) {
            // payload 의 player 이름은 admin 사고 분석용 — 노출 위험성 낮음.
            // 그래도 JSON 안전을 위해 큰따옴표 escape 만 처리.
            payload.append(",\"playerName\":\"")
                    .append(result.playerName().replace("\"", "\\\""))
                    .append("\"");
        }
        payload.append("}");
        audit.append(actor.adminId(), actor.username(), name(), payload.toString());
        return switch (result.state()) {
            case KICKED -> "킥 완료: " + result.playerName();
            case NOT_ONLINE -> "이미 오프라인 상태: " + result.playerName();
            case NO_PLAYER -> "대상 캐릭터 없음 (account=" + accountId + ")";
        };
    }
}
