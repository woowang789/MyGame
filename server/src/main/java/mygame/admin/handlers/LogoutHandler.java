package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import mygame.admin.HttpUtils;
import mygame.admin.auth.AdminAuth;

/** POST /admin/logout — 세션 무효화 후 로그인 페이지로 리다이렉트. */
public final class LogoutHandler implements HttpHandler {

    private final AdminAuth auth;

    public LogoutHandler(AdminAuth auth) {
        this.auth = auth;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtils.sendText(ex, 405, "Method Not Allowed");
            return;
        }
        String token = HttpUtils.cookie(ex, AdminAuth.COOKIE_NAME);
        auth.logout(token);
        HttpUtils.clearCookie(ex, AdminAuth.COOKIE_NAME);
        HttpUtils.redirect(ex, "/admin/login");
    }
}
