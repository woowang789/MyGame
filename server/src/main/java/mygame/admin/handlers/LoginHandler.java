package mygame.admin.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import mygame.admin.HttpUtils;
import mygame.admin.auth.AdminAuth;
import mygame.admin.auth.AdminAuth.Session;
import mygame.admin.view.Html;

/**
 * GET/POST /admin/login. 인증 실패 시 메시지를 그대로 보여주고, 성공 시 세션 쿠키를
 * 굽고 /admin 으로 리다이렉트.
 */
public final class LoginHandler implements HttpHandler {

    private final AdminAuth auth;

    public LoginHandler(AdminAuth auth) {
        this.auth = auth;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            HttpUtils.sendHtml(ex, 200, page(null));
            return;
        }
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            Map<String, String> form = HttpUtils.parseFormBody(ex);
            String username = form.getOrDefault("username", "").trim();
            String password = form.getOrDefault("password", "");
            if (username.isEmpty() || password.isEmpty()) {
                HttpUtils.sendHtml(ex, 400, page("아이디/비밀번호를 입력하세요."));
                return;
            }
            Optional<Session> session = auth.login(username, password);
            if (session.isEmpty()) {
                HttpUtils.sendHtml(ex, 401, page("로그인 실패."));
                return;
            }
            HttpUtils.setCookie(ex, AdminAuth.COOKIE_NAME, session.get().token(),
                    24L * 60 * 60); // 쿠키 maxAge = 세션 TTL 과 동일
            HttpUtils.redirect(ex, "/admin");
            return;
        }
        HttpUtils.sendText(ex, 405, "Method Not Allowed");
    }

    private String page(String error) {
        String errorBlock = error == null ? "" :
                "<p class=\"error\">" + Html.esc(error) + "</p>";
        String body = """
                <section class="login-card">
                  <h1>MyGame Admin</h1>
                  <form method="post" action="/admin/login">
                    <label>아이디 <input name="username" autofocus required></label>
                    <label>비밀번호 <input name="password" type="password" required></label>
                    %s
                    <button type="submit">로그인</button>
                  </form>
                </section>
                """.formatted(errorBlock);
        return Html.bare("Admin 로그인", body);
    }
}
