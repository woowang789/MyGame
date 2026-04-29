package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.BroadcastNoticeCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /admin/actions/broadcast — form: message.
 *
 * <p>입력 검증: 빈 문자열 거부, 200자 길이 상한. 길이 정책은 게임 ChatHandler.MAX_CHAT_LEN
 * 와 동일하게 맞춰 클라이언트 채팅 영역에서 표시될 때 잘리지 않게 한다.
 */
public final class BroadcastNoticeHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(BroadcastNoticeHandler.class);
    private static final int MAX_LEN = 200;

    private final AdminFacade facade;
    private final AuditLogRepository audit;

    public BroadcastNoticeHandler(AdminFacade facade, AuditLogRepository audit) {
        this.facade = facade;
        this.audit = audit;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtils.sendText(ex, 405, "Method Not Allowed");
            return;
        }
        try {
            Map<String, String> form = HttpUtils.parseFormBody(ex);
            String message = form.getOrDefault("message", "").trim();
            if (message.isEmpty()) {
                HttpUtils.sendHtml(ex, 400, errorSpan("메시지가 비어있습니다."));
                return;
            }
            if (message.length() > MAX_LEN) {
                HttpUtils.sendHtml(ex, 400,
                        errorSpan("메시지는 최대 " + MAX_LEN + "자까지 가능합니다."));
                return;
            }
            var cmd = new BroadcastNoticeCommand(facade, message);
            String result = cmd.execute(AuthFilter.sessionOf(ex), audit);
            HttpUtils.sendHtml(ex, 200, "<span class=\"ok\">" + Html.esc(result) + "</span>");
        } catch (Exception e) {
            log.error("공지 송신 실패", e);
            HttpUtils.sendHtml(ex, 500, errorSpan("내부 오류"));
        }
    }

    private static String errorSpan(String text) {
        return "<span class=\"error\">" + Html.esc(text) + "</span>";
    }
}
