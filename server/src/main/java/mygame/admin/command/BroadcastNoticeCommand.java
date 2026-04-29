package mygame.admin.command;

import mygame.admin.AdminFacade;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.auth.AdminAuth.Session;

/**
 * 전체 공지 명령. 호출 시점의 접속 세션 모두에 SYSTEM_NOTICE 패킷을 송신.
 *
 * <p>audit payload 에는 메시지 본문 미리보기(앞 80자) + 송신 성공 세션 수만 기록.
 * 광고/스팸성 본문이 그대로 audit 에 무한 저장되는 걸 막고, 사고 분석에 충분한
 * 단서만 남긴다. 길이 제한은 호출 핸들러가 별도로 더 엄격하게 적용.
 */
public final class BroadcastNoticeCommand implements AdminCommand {

    /** audit payload 에 보관할 본문 길이. 너무 길면 audit_log 가 비대해짐. */
    private static final int PREVIEW_LEN = 80;

    private final AdminFacade facade;
    private final String message;

    public BroadcastNoticeCommand(AdminFacade facade, String message) {
        this.facade = facade;
        this.message = message;
    }

    @Override
    public String name() {
        return "BROADCAST_NOTICE";
    }

    @Override
    public String execute(Session actor, AuditLogRepository audit) {
        int sent = facade.broadcastSystemNotice(message);
        // JSON 안전 escape: 큰따옴표·역슬래시·개행 처리.
        String preview = preview(message);
        String payload = "{\"sent\":" + sent
                + ",\"messageLength\":" + message.length()
                + ",\"preview\":\"" + jsonEscape(preview) + "\"}";
        audit.append(actor.adminId(), actor.username(), name(), payload);
        return "공지 송신 — 수신 세션 " + sent + " 건";
    }

    private static String preview(String s) {
        if (s.length() <= PREVIEW_LEN) return s;
        return s.substring(0, PREVIEW_LEN) + "…";
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
