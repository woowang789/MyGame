package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.ResetPasswordCommand;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /admin/actions/reset-password — form: accountId, newPassword.
 *
 * <p>입력 검증:
 * <ul>
 *   <li>accountId 는 숫자
 *   <li>newPassword 는 최소 6자 (게임 측 AuthService 의 MIN_PASSWORD_LEN 와 일치)
 * </ul>
 * <p>응답은 결과 메시지 부분 HTML — 결과 영역에 swap. 비밀번호는 응답에도 노출하지 않는다.
 */
public final class ResetPasswordHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(ResetPasswordHandler.class);
    /** 게임 AuthService.MIN_PASSWORD_LEN 와 동일. 정책 분기 방지를 위해 같은 값 사용. */
    private static final int MIN_PASSWORD_LEN = 6;
    private static final int MAX_PASSWORD_LEN = 128;

    private final AdminFacade facade;
    private final AuditLogRepository audit;

    public ResetPasswordHandler(AdminFacade facade, AuditLogRepository audit) {
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
            long accountId;
            try {
                accountId = Long.parseLong(form.getOrDefault("accountId", "0"));
            } catch (NumberFormatException nfe) {
                HttpUtils.sendHtml(ex, 400, errorSpan("accountId 가 숫자가 아닙니다."));
                return;
            }
            String newPassword = form.getOrDefault("newPassword", "");
            if (newPassword.length() < MIN_PASSWORD_LEN) {
                HttpUtils.sendHtml(ex, 400,
                        errorSpan("비밀번호는 최소 " + MIN_PASSWORD_LEN + "자 이상이어야 합니다."));
                return;
            }
            if (newPassword.length() > MAX_PASSWORD_LEN) {
                HttpUtils.sendHtml(ex, 400, errorSpan("비밀번호가 너무 깁니다."));
                return;
            }

            var cmd = new ResetPasswordCommand(facade, accountId, newPassword);
            String message = cmd.execute(AuthFilter.sessionOf(ex), audit);
            HttpUtils.sendHtml(ex, 200, "<span class=\"ok\">" + Html.esc(message) + "</span>");
        } catch (Exception e) {
            // 메시지에 비밀번호 입력 정보가 들어가지 않게 일반 메시지로 처리.
            log.error("비밀번호 리셋 실패", e);
            HttpUtils.sendHtml(ex, 500, errorSpan("내부 오류"));
        }
    }

    private static String errorSpan(String text) {
        return "<span class=\"error\">" + Html.esc(text) + "</span>";
    }
}
