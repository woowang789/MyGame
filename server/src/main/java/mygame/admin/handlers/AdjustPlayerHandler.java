package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import mygame.admin.AdminFacade;
import mygame.admin.HttpUtils;
import mygame.admin.audit.AuditLogRepository;
import mygame.admin.command.AdjustPlayerCommand;
import mygame.admin.command.AdjustPlayerCommand.Kind;
import mygame.admin.filter.AuthFilter;
import mygame.admin.view.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POST /admin/actions/adjust-player — form: accountId, kind(MESO|EXP), delta.
 *
 * <p>응답: 결과 메시지 부분 HTML (htmx 가 detail 페이지 내 결과 영역 swap).
 * 페이지 새로고침을 요구하지 않고, 운영자가 입력→피드백 사이클을 빠르게 반복할 수 있게.
 */
public final class AdjustPlayerHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(AdjustPlayerHandler.class);
    /** 단일 입력 상한 — 의도치 않은 큰 값 입력으로 메소가 폭주하지 않도록. */
    private static final long MAX_ABS_DELTA = 1_000_000_000L;

    private final AdminFacade facade;
    private final AuditLogRepository audit;

    public AdjustPlayerHandler(AdminFacade facade, AuditLogRepository audit) {
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
            long accountId = Long.parseLong(form.getOrDefault("accountId", "0"));
            String kindStr = form.getOrDefault("kind", "");
            long delta = Long.parseLong(form.getOrDefault("delta", "0").trim());
            if (delta == 0) {
                HttpUtils.sendHtml(ex, 400, errorSpan("delta 가 0 입니다 (변경 없음)"));
                return;
            }
            if (Math.abs(delta) > MAX_ABS_DELTA) {
                HttpUtils.sendHtml(ex, 400, errorSpan("|delta| 가 너무 큽니다 (한도 " + MAX_ABS_DELTA + ")"));
                return;
            }
            Kind kind;
            try {
                kind = Kind.valueOf(kindStr);
            } catch (IllegalArgumentException ie) {
                HttpUtils.sendHtml(ex, 400, errorSpan("kind 는 MESO 또는 EXP 만 허용"));
                return;
            }

            var cmd = new AdjustPlayerCommand(facade, accountId, kind, delta);
            String message = cmd.execute(AuthFilter.sessionOf(ex), audit);
            HttpUtils.sendHtml(ex, 200, "<span class=\"ok\">" + Html.esc(message) + "</span>");
        } catch (NumberFormatException nfe) {
            HttpUtils.sendHtml(ex, 400, errorSpan("숫자 형식 오류"));
        } catch (Exception e) {
            log.error("플레이어 조정 실패", e);
            HttpUtils.sendHtml(ex, 500, errorSpan("오류: " + e.getMessage()));
        }
    }

    private static String errorSpan(String text) {
        return "<span class=\"error\">" + Html.esc(text) + "</span>";
    }
}
