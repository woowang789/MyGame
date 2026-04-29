package mygame.admin.filter;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Optional;
import mygame.admin.HttpUtils;
import mygame.admin.auth.AdminAuth;
import mygame.admin.auth.AdminAuth.Session;

/**
 * 보호된 관리자 경로 진입 전 세션 쿠키를 검증.
 *
 * <p>인증 실패 시 풀 페이지 요청은 {@code /admin/login} 으로 302 리다이렉트,
 * htmx 부분 요청은 401 + {@code HX-Redirect} 헤더로 클라이언트 측 전환을 유도.
 *
 * <p>학습 의도: Spring Security {@code OncePerRequestFilter} 의 책임을 직접 구현.
 */
public final class AuthFilter extends Filter {

    /** 인증 통과 후 다운스트림 핸들러가 세션 객체를 꺼내 쓰는 키. */
    public static final String SESSION_ATTR = "admin.session";

    private final AdminAuth auth;

    public AuthFilter(AdminAuth auth) {
        this.auth = auth;
    }

    @Override
    public String description() {
        return "Admin session cookie auth";
    }

    @Override
    public void doFilter(HttpExchange ex, Chain chain) throws IOException {
        String token = HttpUtils.cookie(ex, AdminAuth.COOKIE_NAME);
        Optional<Session> session = auth.resolve(token);
        if (session.isEmpty()) {
            // htmx 부분 요청에는 HX-Redirect 헤더로 명확한 신호. 일반 브라우저는 표준 302.
            if (HttpUtils.isHtmx(ex)) {
                ex.getResponseHeaders().set("HX-Redirect", "/admin/login");
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            HttpUtils.redirect(ex, "/admin/login");
            return;
        }
        ex.setAttribute(SESSION_ATTR, session.get());
        chain.doFilter(ex);
    }

    /** 핸들러에서 현재 세션을 꺼내는 헬퍼. AuthFilter 통과한 컨텍스트에서만 호출. */
    public static Session sessionOf(HttpExchange ex) {
        Object o = ex.getAttribute(SESSION_ATTR);
        if (!(o instanceof Session s)) {
            throw new IllegalStateException("AuthFilter 미통과 컨텍스트에서 세션 조회");
        }
        return s;
    }
}
