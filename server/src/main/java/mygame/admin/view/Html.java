package mygame.admin.view;

/**
 * 백오피스 HTML 렌더 헬퍼.
 *
 * <p>j2html / Thymeleaf 같은 의존성 없이도 안전한 HTML 을 만든다 — 학습 목적상
 * 외부 템플릿 엔진을 들이지 않는다. 동적 값은 반드시 {@link #esc(Object)} 로 감싼다.
 */
public final class Html {

    private Html() {}

    /** HTML 특수문자 이스케이프. {@code null} 은 빈 문자열로 치환. */
    public static String esc(Object value) {
        if (value == null) return "";
        String s = value.toString();
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 공통 레이아웃 래퍼. body 는 호출자가 만든 안전한 HTML 문자열. */
    public static String layout(String title, String body) {
        return """
                <!doctype html>
                <html lang="ko">
                <head>
                  <meta charset="utf-8">
                  <title>%s</title>
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <script src="/admin/assets/htmx.min.js"></script>
                  <link rel="stylesheet" href="/admin/assets/admin.css">
                </head>
                <body>
                  <header class="topbar">
                    <a href="/admin" class="brand">MyGame Admin</a>
                    <nav>
                      <a href="/admin">Dashboard</a>
                      <a href="/admin/accounts">Accounts</a>
                      <a href="/admin/audit">Audit</a>
                      <form method="post" action="/admin/logout" class="logout-form">
                        <button type="submit">Logout</button>
                      </form>
                    </nav>
                  </header>
                  <main>%s</main>
                </body>
                </html>
                """.formatted(esc(title), body);
    }

    /** 로그인 페이지처럼 레이아웃이 없는 단순 페이지. */
    public static String bare(String title, String body) {
        return """
                <!doctype html>
                <html lang="ko">
                <head>
                  <meta charset="utf-8">
                  <title>%s</title>
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <link rel="stylesheet" href="/admin/assets/admin.css">
                </head>
                <body class="bare">%s</body>
                </html>
                """.formatted(esc(title), body);
    }
}
